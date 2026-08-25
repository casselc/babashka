(ns babashka.ffi
  "Call functions in native shared libraries.

  Load a library, bind C functions with explicit argument and return types,
  and manage native memory:

      (require '[babashka.ffi :as ffi])
      (ffi/load-system-library \"sqlite3\")
      (def sqlite3-open (ffi/cfn \"sqlite3_open\" [:string :pointer] :int))
      (let [pp (ffi/alloc (ffi/sizeof :pointer))]
        (try (sqlite3-open \"x.db\" pp)
             (ffi/read pp :pointer)
             (finally (ffi/free pp))))

  Use these type keywords:

      :void
      :int :uint :long :ulong :int8 :uint8 :int16 :uint16 :int32
      :uint32 :int64 :uint64 :size_t :ssize_t :char :byte
      :bool :pointer :string :double :float

  Pointers are native addresses stored in Clojure longs. :bool represents a
  one-byte C boolean and returns true or false. Thus, a C predicate does not
  return the truthy number 0. Structs use
  `[:struct [[:field type] ...]]`; a struct in cfn's argument or return list
  is passed by value through libffi.

  Native images use compiled trampolines for common fixed signatures and a
  libffi fallback for other fixed signatures and struct values. Binding
  metadata reports :trampoline, :ffm, or :libffi.

  In native images, variadic calls support up to five total arguments. They
  support at most three fixed arguments and two :double arguments. Callbacks
  support up to four arguments and two :double arguments. Callbacks do not
  support :float. The callback return type must be :void, an integer type, or
  :double. Argument order does not affect these limits. See doc/ffi.md for
  details and workarounds.

  Add a trailing :& to declare a variadic C function. The types before :& are
  the fixed parameters. Each call infers the tail types from the values.
  Integers and pointers use 64-bit integers. C promotion converts floats to
  doubles. Strings use C strings:

      (ffi/defcfn c-open \"open\" [:string :int :&] :int)
      (c-open path O_RDONLY)         ; empty tail
      (c-open path flags 0644)       ; one-int tail, same binding"
  (:refer-clojure :exclude [read])
  (:require [clojure.string :as str])
  (:import [java.lang.foreign Arena FunctionDescriptor Linker MemoryLayout
           MemorySegment SymbolLookup ValueLayout]
           [java.lang.invoke MethodHandle MethodHandles MethodType]
           [java.nio.charset StandardCharsets]))

(set! *warn-on-reflection* true)

;; Everything that touches Linker/handles is lazy: creating downcall handles or
;; upcall stubs during build-time class initialization is forbidden in a native
;; image (addresses would be baked into the image heap).
(def ^:private linker* (delay (Linker/nativeLinker)))

(def ^:private long-carrier?
  #{:int :uint :long :ulong :int8 :uint8 :int16 :uint16 :int32 :uint32
    :int64 :uint64 :size_t :ssize_t :char :byte :pointer :string :bool})

(defn- struct-type? [t]
  (and (vector? t) (= :struct (first t))))

(defn- carrier [t]
  (cond (long-carrier? t) :long
        (= :double t) :double
        (= :float t) :float
        (= :void t) :void
        :else (throw (ex-info (str "babashka.ffi: unknown type " t) {:type t}))))

(def ^:private carrier-layout
  {:long ValueLayout/JAVA_LONG
   :double ValueLayout/JAVA_DOUBLE
   :float ValueLayout/JAVA_FLOAT})

(defn- check-variadic-marker
  "Validates use of the :& variadic marker. Returns the fixed types (the
  vector without the trailing :&) for a variadic signature, nil for a plain
  one."
  [argtypes]
  (when (some #(= :& %) (butlast argtypes))
    (throw (ex-info "babashka.ffi: :& must be last; variadic tail types are inferred per call"
                    {:argtypes argtypes})))
  (when (= :& (peek argtypes))
    (let [fixed (pop argtypes)]
      (when (zero? (count fixed))
        (throw (ex-info "babashka.ffi: a variadic signature needs at least one fixed argtype before :&"
                        {:argtypes argtypes})))
      fixed)))

(defn- tail-type
  "The inferred type of one variadic tail value. Sound because C promotes
  variadic floats to double and small ints to int, and every integer width
  and pointer shares the 64-bit carrier."
  [v]
  (cond
    (or (integer? v) (nil? v) (boolean? v) (instance? MemorySegment v)) :long
    (float? v) :double
    (ratio? v) :double
    (string? v) :string
    :else (throw (ex-info (str "babashka.ffi: cannot infer variadic tail type of " (type v))
                          {:value v}))))

(defn- descriptor ^FunctionDescriptor [argtypes rettype]
  (let [args (into-array MemoryLayout (map #(carrier-layout (carrier %)) argtypes))]
    (if (= :void rettype)
      (FunctionDescriptor/ofVoid args)
      (FunctionDescriptor/of (carrier-layout (carrier rettype)) args))))

;; On the SysV x86-64 and AArch64 ABIs, integer and floating-point arguments
;; are assigned registers from two independent sequences (GP and FP), so
;; argument order BETWEEN those classes does not affect the calling
;; convention as long as nothing spills to the stack (<= 6 integer and <= 8
;; floating args). WITHIN the FP class, float and double share ONE register
;; sequence, so their relative order must be preserved: the sort moves
;; integer carriers first and keeps the floating args in declared order
;; (:double and :float have equal rank; the sort is stable). Not valid on
;; Windows x64 (positional registers) or for variadic calls
;; (stack-positional).
(def ^:private carrier-rank {:long 0 :double 1 :float 1})

(def ^:private windows?
  (.startsWith ^String (System/getProperty "os.name" "") "Windows"))

(defn- sort-permutation
  "Indices that stably sort types by carrier class, or nil when already
  sorted. Always nil on Windows, whose ABI assigns registers by position:
  there the descriptor must preserve the declared order, and the registered
  family holds ordered shapes (see script/gen_ffi_metadata.clj)."
  [types]
  (when-not windows?
    (let [perm (vec (sort-by (fn [i] [(carrier-rank (carrier (nth types i))) i])
                             (range (count types))))]
      (when-not (= perm (vec (range (count types))))
        perm))))

(defn- inverse-permutation [perm]
  (reduce (fn [inv p] (assoc inv (nth perm p) p))
          (vec (repeat (count perm) nil))
          (range (count perm))))

;; -- memory -------------------------------------------------------------------

(defn- segment ^MemorySegment [addr size]
  (.reinterpret (MemorySegment/ofAddress addr) (long size)))

(defn ptr->string
  "Reads the NUL-terminated UTF-8 string at pointer p. Returns nil for a NULL
  pointer."
  [p]
  (when-not (zero? (long p))
    (.getString (segment p Long/MAX_VALUE) 0)))

(defn- with-string-args
  "Calls f with argtypes' :string args replaced by temp C-string pointers,
  freed after the call. Strings passed to C must not be retained by it."
  [argtypes args f]
  (if (some #(= :string %) argtypes)
    (with-open [arena (Arena/ofConfined)]
      (f (mapv (fn [t a]
                 (if (and (= :string t) (string? a))
                   (.address (.allocateFrom ^Arena arena ^String a))
                   a))
               argtypes args)))
    (f args)))

;; One coercion function per type, looked up when a binding is created, so
;; nothing dispatches on the type during a call.
(def ^:private arg-coercer
  (let [as-long (fn [a] (cond (nil? a) 0
                              (instance? MemorySegment a) (.address ^MemorySegment a)
                              :else (long a)))
        as-unsigned-long (fn [a]
                           (cond (nil? a) 0
                                 (instance? MemorySegment a) (.address ^MemorySegment a)
                                 (instance? Number a) (.longValue ^Number a)
                                 :else (long a)))
        as-double (fn [a] (double a))
        as-float (fn [a] (float a))
        as-bool (fn [a] (if a 1 0))]
    (into {:double as-double
           :float as-float
           :bool as-bool
           :uint as-unsigned-long
           :uint8 as-unsigned-long
           :uint16 as-unsigned-long
           :uint32 as-unsigned-long
           :ulong as-unsigned-long
           :uint64 as-unsigned-long
           :size_t as-unsigned-long}
          (map (fn [t] [t as-long]))
          (apply disj long-carrier?
                 [:bool :uint :uint8 :uint16 :uint32
                  :ulong :uint64 :size_t]))))

(defn- coerce-arg [t a] ((arg-coercer t) a))

(defn- narrow-ret [t raw]
  (case t
    :void nil
    :bool (not (zero? (long raw)))
    (:int :int32) (long (unchecked-int (long raw)))
    (:uint :uint32) (bit-and (long raw) 0xFFFFFFFF)
    :int16 (long (unchecked-short (long raw)))
    :uint16 (bit-and (long raw) 0xFFFF)
    (:int8 :byte :char) (long (unchecked-byte (long raw)))
    :uint8 (bit-and (long raw) 0xFF)
    :string (let [p (long raw)] (when-not (zero? p) (ptr->string p)))
    raw))

;; -- libraries ----------------------------------------------------------------

(def ^:private libraries (atom []))

(defn- os-key []
  (let [os (System/getProperty "os.name")]
    (cond (.startsWith ^String os "Mac") :mac
          (.startsWith ^String os "Windows") :windows
          :else :linux)))

(defn- search-dirs
  "Directories probed for bare library names after the system's own dlopen
  search fails. On Linux LD_LIBRARY_PATH comes first: dlopen honors it by
  itself, but the versioned-soname glob cannot."
  []
  (case (os-key)
    :mac ["/opt/homebrew/lib" "/usr/local/lib" "/opt/local/lib" "/usr/lib"]
    :windows []
    (concat
     (when-let [p (System/getenv "LD_LIBRARY_PATH")]
       (remove str/blank? (str/split p #":")))
     (let [multiarch (if (= "aarch64" (System/getProperty "os.arch"))
                      "aarch64-linux-gnu"
                      "x86_64-linux-gnu")]
      ["/usr/local/lib"
       ;; RHEL family and the FreeBSD linux compat layer keep libraries here
       "/usr/lib64"
       "/usr/lib"
       (str "/usr/lib/" multiarch)
       ;; unmerged-/usr RHEL family and the FreeBSD linux compat layer
       "/lib64"
       "/lib"
       ;; unmerged-/usr systems keep runtime libraries here
       (str "/lib/" multiarch)]))))

(def ^:private last-lookup-error (volatile! nil))

(defn- try-lookup ^SymbolLookup [^String path]
  (try (SymbolLookup/libraryLookup path (Arena/global))
       (catch java.lang.IllegalCallerException e
         ;; native access denied: no candidate can ever load, so fail loud
         ;; instead of reporting a misleading not-found
         (throw (ex-info (str "babashka.ffi: native access is not enabled on this JVM: "
                              (ex-message e)
                              " (run with --enable-native-access=ALL-UNNAMED)")
                         {:path path} e)))
       (catch Throwable e
         (vreset! last-lookup-error e)
         nil)))

(defn- lookup-one
  "One path through the full search: as given, then, for a bare name, the
  common install directories. A {:path :lookup} map, nil when not found."
  [^String path]
  (or (when-let [lk (try-lookup path)]
        {:path path :lookup lk})
      (when-not (.contains path "/")
        (some (fn [dir]
                (let [p (str dir "/" path)]
                  (when-let [lk (try-lookup p)]
                    {:path p :lookup lk})))
              (search-dirs)))))

(defn load-library
  "Loads a shared library and adds it to the symbol search.

  Use load-system-library for file names that follow platform conventions.

  lib can be a path, a vector of candidates, or a map of operating systems to
  candidates. The function tries vector entries in order. An operating-system
  map uses the keys :mac, :linux, and :windows:

      (ffi/load-library
        {:mac [\"/opt/homebrew/opt/openssl@3/lib/libcrypto.3.dylib\"
               \"/usr/local/opt/openssl@3/lib/libcrypto.3.dylib\"]
         :linux \"libcrypto.so.3\"})

  :darwin is an alias for :mac. For a bare name, the function also searches
  common installation directories. Returns a library map whose :path value
  identifies the loaded candidate. The map can be the first argument to cfn.
  In that form, cfn searches only this library."
  [lib]
  (let [paths (cond
                (map? lib)
                (let [v (or (get lib (os-key))
                            (when (= :mac (os-key)) (get lib :darwin))
                            (throw (ex-info (str "babashka.ffi: no library for OS " (os-key))
                                            {:libs lib})))]
                  (mapv str (if (vector? v) v [v])))
                (vector? lib) (mapv str lib)
                :else [(str lib)])
        m (or (some lookup-one paths)
              (throw (ex-info (str "babashka.ffi: cannot load library: "
                                   (str/join ", " paths)
                                   " (bare names also searched in "
                                   (pr-str (vec (search-dirs))) ")")
                              {:library lib}
                              @last-lookup-error)))]
    (swap! libraries conj (:lookup m))
    m))

(defn load-system-library
  "Loads a shared library by its short name. For example, \"z\" selects
  libz.dylib, libz.so, or z.dll. On Linux, the search also includes versioned
  names such as libz.so.1. Returns the same library map as load-library."
  [name]
  (case (os-key)
    :mac (load-library (str "lib" name ".dylib"))
    :windows (load-library (str name ".dll"))
    (let [base (str "lib" name ".so")]
      (or (try (load-library base) (catch Exception _ nil))
          ;; glob lib<name>.so.* in the search dirs
          (when-let [m (some (fn [dir]
                               (let [d (java.io.File. ^String dir)
                                     ;; newest soname first, numerically:
                                     ;; libz.so.10 beats libz.so.9
                                     vkey (fn [^String f]
                                            (mapv #(or (parse-long %) -1)
                                                  (rest (str/split (subs f (count base)) #"\."))))
                                     newest-first (fn [x y]
                                                    (let [a (vkey x) b (vkey y)
                                                          n (max (count a) (count b))
                                                          pad #(into % (repeat (- n (count %)) -1))]
                                                      (compare (pad b) (pad a))))
                                     cands (when (.isDirectory d)
                                             (->> (.list d)
                                                  (filter #(.startsWith ^String % (str base ".")))
                                                  (sort newest-first)))]
                                 (some (fn [c]
                                         (let [p (str dir "/" c)]
                                           (when-let [lk (try-lookup p)]
                                             {:path p :lookup lk})))
                                       cands)))
                             (search-dirs))]
            (swap! libraries conj (:lookup m))
            m)
          (throw (ex-info (str "babashka.ffi: cannot find library " name
                               " (tried " base " and " base ".* in "
                               (pr-str (vec (search-dirs))) ")")
                          {:library name}
                          @last-lookup-error))))))

(defn- lookup-symbol ^MemorySegment [lib ^String sym]
  (let [lib (if (map? lib) (:lookup lib) lib)
        lookups (if lib [lib] (conj @libraries (.defaultLookup ^Linker @linker*)))]
    (some (fn [^SymbolLookup l] (.orElse (.find l sym) nil)) lookups)))

(defn- require-symbol ^MemorySegment [lib ^String sym]
  (or (lookup-symbol lib sym)
      (throw (ex-info (str "babashka.ffi: symbol not found: " sym) {:symbol sym}))))

(defn find-symbol
  "Finds sym and returns its native address as a Clojure long, or nil.

  With one argument, searches loaded libraries and the default system lookup.
  With a library map returned by load-library, searches only that library."
  ([sym]
   (some-> (lookup-symbol nil (str sym)) .address))
  ([lib sym]
   (some-> (lookup-symbol lib (str sym)) .address)))

;; -- foreign functions --------------------------------------------------------

(def ^:private native-image?
  (boolean (System/getProperty "org.graalvm.nativeimage.imagecode")))

;; In a native image, FFM downcall handles are interpreted (~3.4us/call);
;; the generated trampolines (babashka.impl.FfiTrampoline) call through raw
;; function pointers as compiled direct calls (~2ns). One per canonical
;; shape; loaded only in the image, never on the JVM, where the FFM handle
;; path is JIT-compiled and fast.
(def ^:private trampoline-ids
  (when native-image?
    @(requiring-resolve 'babashka.impl.ffi-trampolines/ids)))

(def ^:private trampoline-invoker
  (when native-image?
    (requiring-resolve 'babashka.impl.ffi-trampolines/invoker)))

(defn- shape-key [types* rettype]
  (let [c {:long "J" :double "D" :float "F"}]
    (str (if (= :void rettype) "V" (c (carrier rettype)))
         "_"
         (apply str (map #(c (carrier %)) types*)))))

(defn- unsupported-ex [sym argtypes rettype why]
  (ex-info (str "babashka.ffi: unsupported signature: " sym " "
                (pr-str argtypes) " -> " rettype ". " why ". "
                "Workaround: call through libffi (ffi-libffi.clj in the babashka repo shows how). "
                "Please report this signature in a babashka issue; it can likely be supported.")
           {:symbol sym :argtypes argtypes :rettype rettype}))

(def ^:private variadic-limits
  "variadic calls support up to 5 args total, at most 3 fixed, at most 2 :double, and a :void, integer or pointer return")

(declare ^:private fixed-cfn)
(declare alloc free sizeof read write)

;; -- libffi fixed-signature fallback -----------------------------------------

(defn- load-libffi []
  (case (os-key)
    :windows (load-library ["libffi-8.dll" "libffi.dll" "ffi.dll"])
    (load-system-library "ffi")))

(defn- default-libffi-abi []
  ;; libffi's public ffi_abi enum is target-specific. These are its default
  ;; 64-bit ABI values: AArch64 SYSV=1, x86-64 UNIX64=2, Windows x64=1.
  (cond
    (= :windows (os-key)) 1
    (= "aarch64" (System/getProperty "os.arch")) 1
    :else 2))

(def ^:private primitive-ffi-type-symbol
  {:void "ffi_type_void"
   :bool "ffi_type_uint8"
   :int8 "ffi_type_sint8"
   :byte "ffi_type_sint8"
   :char "ffi_type_sint8"
   :uint8 "ffi_type_uint8"
   :int16 "ffi_type_sint16"
   :uint16 "ffi_type_uint16"
   :int "ffi_type_sint32"
   :int32 "ffi_type_sint32"
   :uint "ffi_type_uint32"
   :uint32 "ffi_type_uint32"
   :long "ffi_type_sint64"
   :int64 "ffi_type_sint64"
   :ssize_t "ffi_type_sint64"
   :ulong "ffi_type_uint64"
   :uint64 "ffi_type_uint64"
   :size_t "ffi_type_uint64"
   :float "ffi_type_float"
   :double "ffi_type_double"
   :pointer "ffi_type_pointer"
   :string "ffi_type_pointer"})

(def ^:private libffi*
  (delay
    (let [library (load-libffi)]
      {:library library
       :prep-cif (fixed-cfn library "ffi_prep_cif"
                            [:pointer :int :uint :pointer :pointer] :int)
       :call (fixed-cfn library "ffi_call"
                        [:pointer :pointer :pointer :pointer] :void)})))

;; ffi_type values for structs own their NUL-terminated elements array. They
;; are cached for process lifetime, matching the lifetime of cfn bindings.
(def ^:private libffi-types* (atom {}))

(declare ^:private libffi-type)

(defn- struct-field-types [type]
  (let [fields (second type)]
    (when-not (and (= 2 (count type))
                   (vector? fields)
                   (every? #(and (vector? %) (= 2 (count %)) (keyword? (first %)))
                           fields)
                   (= (count fields) (count (distinct (map first fields)))))
      (throw (ex-info (str "babashka.ffi: invalid struct type " (pr-str type))
                      {:type type})))
    (mapv second fields)))

(defn- make-struct-ffi-type [type]
  (let [element-types (mapv libffi-type (struct-field-types type))
        pointer-size (sizeof :pointer)
        elements (alloc (* pointer-size (inc (count element-types))))
        ffi-type (alloc 24)]
    (doseq [[index element] (map-indexed vector element-types)]
      (write elements :pointer (* pointer-size index) element))
    (write elements :pointer (* pointer-size (count element-types)) 0)
    ;; ffi_type {size_t size; ushort alignment; ushort type; **elements}.
    ;; libffi fills size/alignment while preparing the CIF.
    (write ffi-type :size_t 0 0)
    (write ffi-type :uint16 8 0)
    (write ffi-type :uint16 10 13) ; FFI_TYPE_STRUCT
    (write ffi-type :pointer 16 elements)
    ffi-type))

(defn- libffi-type [type]
  (or (get @libffi-types* type)
      (let [library (:library (force libffi*))
            pointer
            (if (struct-type? type)
              (make-struct-ffi-type type)
              (if-let [symbol (get primitive-ffi-type-symbol type)]
                (.address (require-symbol library symbol))
                (throw (ex-info (str "babashka.ffi: unknown type " type)
                                {:type type}))))]
        (swap! libffi-types* assoc type pointer)
        pointer)))

(defn- libffi-cif [argtypes rettype]
  (let [{:keys [prep-cif]} (force libffi*)
        pointer-size (sizeof :pointer)
        arg-type-pointers (mapv libffi-type argtypes)
        return-type-pointer (libffi-type rettype)
        atypes (alloc (max pointer-size (* pointer-size (count argtypes))))
        cif (alloc 256)]
    (doseq [[index pointer] (map-indexed vector arg-type-pointers)]
      (write atypes :pointer (* pointer-size index) pointer))
    (let [status (prep-cif cif (default-libffi-abi) (count argtypes)
                           return-type-pointer atypes)]
      (when-not (zero? status)
        (free cif)
        (free atypes)
        (throw (ex-info "babashka.ffi: ffi_prep_cif failed"
                        {:status status
                         :argtypes argtypes
                         :rettype rettype}))))
    {:cif cif
     :atypes atypes
     :return-type-pointer return-type-pointer}))

(defn- libffi-write-argument! [pointer type value]
  (case type
    :string (write pointer :pointer 0 value)
    :pointer (write pointer :pointer 0 value)
    (write pointer type 0 value)))

(defn- libffi-read-result [pointer type]
  (cond
    (= :void type) nil
    (struct-type? type) pointer
    (= :string type) (ptr->string (read pointer :pointer))
    :else (read pointer type)))

(defn- libffi-cfn [lib sym argtypes rettype]
  ;; Force this at bind time so an unavailable libffi produces a focused
  ;; signature error before any native call is attempted.
  (let [{ffi-call :call} (force libffi*)
        {:keys [cif return-type-pointer]} (libffi-cif argtypes rettype)
        function-pointer (.address (require-symbol lib sym))
        pointer-size (sizeof :pointer)
        n (count argtypes)
        return-size (if (= :void rettype)
                      0
                      (if (struct-type? rettype)
                        ;; ffi_prep_cif populated ffi_type.size.
                        (read return-type-pointer :size_t)
                        (sizeof rettype)))
        arity-error (fn [got]
                      (throw (ex-info (str "babashka.ffi: " sym " expects " n
                                           " args, got " got)
                                      {:symbol sym})))]
    (with-meta
      (fn [& args]
        (if-not (= n (count args))
          (arity-error (count args))
          (with-string-args argtypes (vec args)
            (fn [args]
              (let [argument-storage (atom [])
                    avalues (alloc (max pointer-size (* pointer-size n)))
                    rvalue (when (pos? return-size) (alloc return-size))
                    keep-rvalue? (atom false)]
                (try
                  (doseq [[index [type value]]
                          (map-indexed vector (map vector argtypes args))]
                    (let [storage
                          (if (struct-type? type)
                            (long value)
                            (let [pointer (alloc (sizeof type))]
                              (swap! argument-storage conj pointer)
                              (libffi-write-argument! pointer type value)
                              pointer))]
                      (write avalues :pointer (* pointer-size index) storage)))
                  (ffi-call cif function-pointer (or rvalue 0) avalues)
                  (let [result (libffi-read-result rvalue rettype)]
                    ;; A struct return is caller-owned native memory. Every
                    ;; scalar result is copied before its temporary is freed.
                    (when (struct-type? rettype)
                      (reset! keep-rvalue? true))
                    result)
                  (finally
                    (doseq [pointer @argument-storage] (free pointer))
                    (when (and rvalue (not @keep-rvalue?)) (free rvalue))
                    (free avalues))))))))
      {:babashka.ffi/backend :libffi})))

(defn- variadic-cfn
  "A variadic binding: fixed types declared, tail inferred per call. One FFM
  handle per distinct tail shape, cached."
  [lib sym fixed argtypes rettype]
  (doseq [t fixed] (carrier t))
  (carrier rettype)
  (when (and native-image?
             (or (> (count fixed) 3)
                 (some #(= :float (carrier %)) fixed)
                 ;; variadic descriptors are only registered for void and
                 ;; integer returns
                 (#{:double :float} (carrier rettype))))
    (throw (unsupported-ex sym argtypes rettype variadic-limits)))
  (let [nf (count fixed)
        cache (atom {})
        caller-for
        (fn [tail-types]
          (or (get @cache tail-types)
              (let [all-types (into fixed tail-types)]
                (when (and native-image?
                           (or (> (count all-types) 5)
                               (> (count (filter #(= :double (carrier %)) all-types)) 2)))
                  (throw (unsupported-ex sym argtypes rettype
                                         (str variadic-limits ", called with tail "
                                              (pr-str tail-types)))))
                (let [handle (.downcallHandle
                              ^Linker @linker*
                              (require-symbol lib sym)
                              (descriptor all-types rettype)
                              (into-array java.lang.foreign.Linker$Option
                                          [(java.lang.foreign.Linker$Option/firstVariadicArg nf)]))
                      caller (fn [^objects arr]
                               (.invokeWithArguments ^MethodHandle handle arr))]
                  (swap! cache assoc tail-types caller)
                  caller))))]
    (with-meta
      (fn [& args]
        (when (< (count args) nf)
          (throw (ex-info (str "babashka.ffi: " sym " expects at least " nf
                               " args, got " (count args))
                          {:symbol sym})))
        (let [args (vec args)
              tail-types (mapv tail-type (subvec args nf))
              all-types (into fixed tail-types)
              caller (caller-for tail-types)]
          (with-string-args all-types args
            (fn [args]
              (narrow-ret rettype
                          (caller (object-array
                                   (map-indexed (fn [i a] (coerce-arg (all-types i) a))
                                                args))))))))
      {:babashka.ffi/backend :ffm})))

(defn cfn
  "Creates a Clojure function that calls C function sym. argtypes is a vector
  of type keywords. rettype is a type keyword.

  With a library map, cfn searches only that library. Without one, cfn
  searches all loaded libraries and then the default system lookup. The first
  call resolves the symbol and creates the call handle. You can create the
  binding before you load its library.

  A trailing :& declares a variadic C function. The types before :& are the
  fixed parameters. Each call infers the tail types from its values."
  ([sym argtypes rettype] (cfn nil sym argtypes rettype))
  ([lib sym argtypes rettype]
   (when-not (string? sym)
     (throw (ex-info (str "babashka.ffi: C symbol must be a string: " (pr-str sym))
                     {:sym sym})))
   (when (some #(= :void %) argtypes)
     (throw (ex-info (str "babashka.ffi: :void is not an argument type: " (pr-str argtypes))
                     {:argtypes argtypes})))
   (if-let [fixed (check-variadic-marker argtypes)]
     (variadic-cfn lib sym fixed argtypes rettype)
     (fixed-cfn lib sym argtypes rettype))))

(defn- direct-fixed-cfn
  [lib sym argtypes rettype]
  (let [types argtypes
        perm (sort-permutation types)
        types* (if perm (mapv types perm) types)
        ;; raw invoker: a fn of the coerced argument array. In a native
        ;; image a generated trampoline (compiled direct call) when the
        ;; shape has one; otherwise an FFM downcall handle.
        tramp-id (get trampoline-ids (shape-key types* rettype))
        ;; in a native image every supported shape is known ahead of time
        ;; (ordered shapes on Windows, canonical elsewhere), so reject
        ;; unsupported signatures here with a useful message instead of
        ;; GraalVM's rebuild-the-image error at call time
        _ (when (and native-image? (not tramp-id))
            (throw (unsupported-ex sym argtypes rettype
                                   "see the signature limits in doc/ffi.md")))
        raw (if tramp-id
              (delay (trampoline-invoker tramp-id (.address (require-symbol lib sym))))
              (delay
                (let [handle (.downcallHandle ^Linker @linker*
                                              (require-symbol lib sym)
                                              (descriptor types* rettype)
                                              (make-array java.lang.foreign.Linker$Option 0))]
                  (fn [^objects arr] (.invokeWithArguments ^MethodHandle handle arr)))))
         n (count types)
         strings? (boolean (some #(= :string %) types*))
         coercers ^objects (object-array (map arg-coercer types*))
         call (fn [^objects arr]
                (narrow-ret rettype ((force raw) arr)))
         ;; `in` holds the arguments as written; the call needs them in
         ;; descriptor order, coerced. Without a permutation that is done in
         ;; place, with one it fills a second array through the permutation,
         ;; and either way nothing allocates a seq or a vector.
         perm-arr (when perm (int-array perm))
         fill (if perm-arr
                (fn ^objects [^objects in]
                  (let [out (object-array n)]
                    (dotimes [i n]
                      (aset out i ((aget coercers i) (aget in (aget ^ints perm-arr i)))))
                    out))
                (fn ^objects [^objects in]
                  (dotimes [i n]
                    (aset in i ((aget coercers i) (aget in i))))
                  in))
         coerce-all (fn ^objects [args] (fill (object-array args)))
         ;; strings need a temporary arena that has to outlive the call
         general (fn [args]
                   (let [args* (if perm (mapv (vec args) perm) (vec args))]
                     (with-string-args types* args*
                       (fn [args]
                         (let [arr (object-array args)]
                           (dotimes [i n]
                             (aset arr i ((aget coercers i) (aget arr i))))
                           (call arr))))))
         arity-error (fn [got]
                       (throw (ex-info (str "babashka.ffi: " sym " expects " n
                                            " args, got " got)
                                       {:symbol sym})))]
     (with-meta
       (if strings?
         (fn [& args]
           (if (= (count args) n) (general args) (arity-error (count args))))
         ;; fixed arities, no seq allocation, no intermediate vectors
         (case n
             0 (fn [] (call (object-array 0)))
             1 (fn [a] (call (fill (doto (object-array 1) (aset 0 a)))))
             2 (fn [a b] (call (fill (doto (object-array 2) (aset 0 a) (aset 1 b)))))
             3 (fn [a b d] (call (fill (doto (object-array 3)
                                        (aset 0 a) (aset 1 b) (aset 2 d)))))
             4 (fn [a b d e] (call (fill (doto (object-array 4)
                                           (aset 0 a) (aset 1 b)
                                           (aset 2 d) (aset 3 e)))))
             (fn [& args]
               (if (= (count args) n) (call (coerce-all args)) (arity-error (count args))))))
       ;; which call mechanism this binding uses, for tests and diagnostics:
       ;; :trampoline = compiled direct call, :ffm = downcall handle
       ;; (interpreted in a native image)
       {:babashka.ffi/backend (if tramp-id :trampoline :ffm)})))

(defn- fixed-cfn [lib sym argtypes rettype]
  (if (or (struct-type? rettype) (some struct-type? argtypes))
    (libffi-cfn lib sym argtypes rettype)
    (let [perm (sort-permutation argtypes)
          ordered-types (if perm (mapv argtypes perm) argtypes)
          fallback? (and native-image?
                         (nil? (get trampoline-ids
                                    (shape-key ordered-types rettype))))]
      (if fallback?
        (libffi-cfn lib sym argtypes rettype)
        (direct-fixed-cfn lib sym argtypes rettype)))))

(defn signature-backend
  "Reports the call mechanism a cfn fixed signature selects without resolving
  a C symbol. Returns :trampoline, :ffm, or :libffi."
  [argtypes rettype]
  (when (some #(= :void %) argtypes)
    (throw (ex-info (str "babashka.ffi: :void is not an argument type: "
                         (pr-str argtypes))
                    {:argtypes argtypes})))
  (if (or (struct-type? rettype) (some struct-type? argtypes))
    (do
      (doseq [type (cond-> argtypes
                     (struct-type? rettype) (conj rettype))]
        (when (struct-type? type) (struct-field-types type)))
      :libffi)
    (let [perm (sort-permutation argtypes)
          ordered-types (if perm (mapv argtypes perm) argtypes)
          key (shape-key ordered-types rettype)]
      ;; shape-key also validates every scalar type.
      (if native-image?
        (if (get trampoline-ids key) :trampoline :libffi)
        :ffm))))

(defmacro defcfn
  "Defines name as a C function binding created by cfn:

      (defcfn sqlite3-open \"sqlite3_open\" [:string :pointer] :int)

      (defcfn sqlite3-open
        \"Opens the database at path, storing the handle in out-param pp.\"
        \"sqlite3_open\" [:string :pointer] :int)

  An optional docstring and attribute map can precede the C symbol. The final
  three arguments are the C symbol, argument types, and return type. defcfn
  preserves all metadata on name. This metadata includes ^:private."
  {:arglists '([name docstring? attr-map? sym argtypes rettype])}
  [name & args]
  (when (< (count args) 3)
    (throw (ex-info "babashka.ffi: defcfn needs a C symbol, argtypes and a return type"
                    {:name name})))
  (let [[sym argtypes rettype] (take-last 3 args)
        prefix (drop-last 3 args)
        docstring (first (filter string? prefix))
        attr-map (first (filter map? prefix))]
    (when-not (and (<= (count prefix) 2)
                   (<= (count (filter string? prefix)) 1)
                   (<= (count (filter map? prefix)) 1)
                   (every? #(or (string? %) (map? %)) prefix))
      (throw (ex-info "babashka.ffi: defcfn takes at most a docstring and an attribute map before the C symbol"
                      {:name name})))
    `(def ~(with-meta name (cond-> (meta name)
                             attr-map (merge attr-map)
                             docstring (assoc :doc docstring)))
       (cfn ~sym ~argtypes ~rettype))))

;; -- manual memory ------------------------------------------------------------

(def ^:private crt-lib
  ;; Windows' default lookup does not expose the C runtime
  (delay (when (= :windows (os-key))
           (or (try (load-library "msvcrt.dll") (catch Exception _ nil))
               (try (load-library "ucrtbase.dll") (catch Exception _ nil))))))

(def ^:private c-calloc (delay (cfn @crt-lib "calloc" [:size_t :size_t] :pointer)))
(def ^:private c-free (delay (cfn @crt-lib "free" [:pointer] :void)))

(defn alloc
  "Allocates n bytes of zeroed native memory and returns its pointer. Release
  the pointer with free."
  [n]
  (@c-calloc 1 n))

(defn free
  "Releases memory allocated by alloc or string->ptr."
  [p]
  (@c-free p))

(def ^:private sizes
  {:int 4 :uint 4 :int32 4 :uint32 4 :float 4
   :long 8 :ulong 8 :int64 8 :uint64 8 :size_t 8 :ssize_t 8
   :pointer 8 :string 8 :double 8
   :int16 2 :uint16 2 :int8 1 :uint8 1 :byte 1 :char 1 :bool 1})

(defn- align-up [offset alignment]
  (let [remainder (mod offset alignment)]
    (if (zero? remainder) offset (+ offset (- alignment remainder)))))

(declare ^:private type-layout)

(defn- struct-layout [type]
  (let [fields (second type)
        _ (struct-field-types type)
        built
        (reduce
         (fn [{:keys [offset alignment field-info]} [field-name field-type]]
           (let [child (type-layout field-type)
                 field-offset (align-up offset (:alignment child))
                 child-fields
                 (if (:fields child)
                   (into {}
                         (map (fn [[path info]]
                                [(into [field-name] path)
                                 (update info :offset + field-offset)]))
                         (:fields child))
                   {[field-name] {:offset field-offset :type field-type}})]
             {:offset (+ field-offset (:size child))
              :alignment (max alignment (:alignment child))
              :field-info (merge field-info child-fields)}))
         {:offset 0 :alignment 1 :field-info {}}
         fields)
        size (align-up (:offset built) (:alignment built))]
    {:type type
     :size size
     :alignment (:alignment built)
     :fields (:field-info built)}))

(defn- type-layout [type]
  (if (struct-type? type)
    (struct-layout type)
    (if-let [size (get sizes type)]
      {:type type :size size :alignment size}
      (throw (ex-info (str "babashka.ffi: unknown type " type) {:type type})))))

(defn layout
  "Computes C size, alignment and nested field offsets for a struct type."
  [type]
  (when-not (struct-type? type)
    (throw (ex-info (str "babashka.ffi: layout requires a struct type, got "
                         (pr-str type))
                    {:type type})))
  (type-layout type))

(defn layout-size
  "Returns the byte size of a layout returned by layout."
  [layout]
  (:size layout))

(defn sizeof
  "Returns the size, in bytes, of a scalar keyword or struct type."
  [t]
  (:size (type-layout t)))

(defn read
  "Reads a value of type t from pointer p. The optional byte offset defaults
  to 0."
  ([p t] (read p t 0))
  ([p t offset]
   (let [seg (segment p (+ (long offset) (long (sizeof t))))
         off (long offset)]
     (case t
       (:int :int32) (long (.get seg ValueLayout/JAVA_INT_UNALIGNED off))
       (:uint :uint32) (bit-and (long (.get seg ValueLayout/JAVA_INT_UNALIGNED off)) 0xFFFFFFFF)
       (:long :ulong :int64 :uint64 :size_t :ssize_t :pointer)
       (.get seg ValueLayout/JAVA_LONG_UNALIGNED off)
       :int16 (long (.get seg ValueLayout/JAVA_SHORT_UNALIGNED off))
       :uint16 (bit-and (long (.get seg ValueLayout/JAVA_SHORT_UNALIGNED off)) 0xFFFF)
       :bool (not (zero? (long (.get seg ValueLayout/JAVA_BYTE off))))
      (:int8 :byte :char) (long (.get seg ValueLayout/JAVA_BYTE off))
       :uint8 (bit-and (long (.get seg ValueLayout/JAVA_BYTE off)) 0xFF)
       :double (.get seg ValueLayout/JAVA_DOUBLE_UNALIGNED off)
       :float (.get seg ValueLayout/JAVA_FLOAT_UNALIGNED off)
       :string (ptr->string (.get seg ValueLayout/JAVA_LONG_UNALIGNED off))
       (throw (ex-info (str "babashka.ffi: cannot read type " t) {:type t}))))))

(defn write
  "Writes value v as type t to pointer p. The optional byte offset defaults
  to 0. Returns nil."
  ([p t v] (write p t 0 v))
  ([p t offset v]
   (let [seg (segment p (+ (long offset) (long (sizeof t))))
         off (long offset)]
     (case t
       (:int :uint :int32 :uint32) (.set seg ValueLayout/JAVA_INT_UNALIGNED off (unchecked-int (long v)))
       (:long :int64 :ssize_t :pointer)
       (.set seg ValueLayout/JAVA_LONG_UNALIGNED off (long v))
       (:ulong :uint64 :size_t)
       (.set seg ValueLayout/JAVA_LONG_UNALIGNED off (.longValue ^Number v))
       (:int16 :uint16) (.set seg ValueLayout/JAVA_SHORT_UNALIGNED off (unchecked-short (long v)))
       :bool (.set seg ValueLayout/JAVA_BYTE off (unchecked-byte (if v 1 0)))
       (:int8 :uint8 :byte :char) (.set seg ValueLayout/JAVA_BYTE off (unchecked-byte (long v)))
       :double (.set seg ValueLayout/JAVA_DOUBLE_UNALIGNED off (double v))
       :float (.set seg ValueLayout/JAVA_FLOAT_UNALIGNED off (float v))
       (throw (ex-info (str "babashka.ffi: cannot write type " t) {:type t})))
     nil)))

(defn read-field
  "Reads a scalar field by its keyword path from pointer p and layout l."
  [p l path]
  (if-let [{:keys [offset type]} (get (:fields l) path)]
    (read p type offset)
    (throw (ex-info "babashka.ffi: unknown struct field path"
                    {:path path :layout (:type l)}))))

(defn write-field
  "Writes a scalar field by its keyword path to pointer p and layout l."
  [p l path value]
  (if-let [{:keys [offset type]} (get (:fields l) path)]
    (write p type offset value)
    (throw (ex-info "babashka.ffi: unknown struct field path"
                    {:path path :layout (:type l)}))))

(defn read-array
  "Copies n bytes from native pointer p into a byte array."
  [p n]
  (if (zero? n)
    (byte-array 0)
    (.toArray (segment p n) ValueLayout/JAVA_BYTE)))

(defn write-array
  "Copies a byte array to native pointer p. Returns nil."
  [p value]
  (let [n (alength ^bytes value)]
    (when (pos? n)
      (MemorySegment/copy ^bytes value 0 (segment p n)
                          ValueLayout/JAVA_BYTE 0 n)))
  nil)

(defn read-bytes
  "Reads n UTF-8 bytes from native pointer p. Embedded NUL bytes are kept."
  [p n]
  (String. ^bytes (read-array p n) StandardCharsets/UTF_8))

(defn write-bytes
  "Writes UTF-8 bytes for s to native pointer p and returns the byte count."
  [p s]
  (let [value (.getBytes ^String s StandardCharsets/UTF_8)]
    (write-array p value)
    (alength value)))

(defn string->ptr
  "Copies s to newly allocated native memory as a NUL-terminated UTF-8
  string. Returns its pointer. Release the pointer with free."
  [^String s]
  (let [bytes (.getBytes s "UTF-8")
        n (inc (alength bytes))
        p (alloc n)
        seg (segment p n)]
    (MemorySegment/copy bytes 0 seg ValueLayout/JAVA_BYTE 0 (alength bytes))
    p))

(def null
  "The NULL pointer address."
  0)

(defn null?
  "Returns true for a NULL pointer. Returns false for all other pointers."
  [p]
  (zero? (long p)))

;; -- callbacks ----------------------------------------------------------------

(def ^:private callback-arenas (atom {}))

(defn callback
  "Creates a C function pointer that invokes Clojure function f. argtypes and
  rettype use the same type keywords as cfn. Pointer and integer arguments
  are Clojure longs. :bool arguments are booleans.

  Returns the function pointer as a Clojure long. The callback remains valid
  until free-callback releases it."
  [f argtypes rettype]
  (doseq [t argtypes] (carrier t))
  (carrier rettype)
  (when (some #(= :void %) argtypes)
    (throw (ex-info (str "babashka.ffi: :void is not an argument type: " (pr-str argtypes))
                    {:argtypes argtypes})))
  (when (and native-image?
             (or (> (count argtypes) 4)
                 (some #(= :float (carrier %)) argtypes)
                 (> (count (filter #(= :double (carrier %)) argtypes)) 2)
                 (= :float (carrier rettype))))
    (throw (unsupported-ex "callback" argtypes rettype
                           "callbacks support up to 4 args, at most 2 :double, no :float, and a :void, integer or :double return")))
  (let [;; f returns arbitrary Clojure values and receives raw carriers:
        ;; coerce the result to the declared return type (a Boolean or
        ;; Integer crossing the upcall boundary uncaught would kill the VM)
        ;; and give :bool arguments to f as booleans
        ret-c (when-not (= :void rettype) (arg-coercer rettype))
        bool-args (mapv #(= :bool %) argtypes)
        f (if (or ret-c (some true? bool-args))
            (let [g f]
              (fn [& args]
                (let [r (apply g (map-indexed
                                  (fn [i a]
                                    (if (nth bool-args i) (not (zero? (long a))) a))
                                  args))]
                  (if ret-c (ret-c r) r))))
            f)
        n (count argtypes)
        perm (sort-permutation argtypes)
        inv (when perm (inverse-permutation perm))
        argtypes (if perm (mapv argtypes perm) argtypes)
        f (if perm
            (fn [& sorted]
              (let [sorted (vec sorted)]
                (apply f (map (fn [j] (nth sorted (nth inv j))) (range n)))))
            f)
        ret-carrier (carrier rettype)
        obj-type (MethodType/methodType Object ^"[Ljava.lang.Class;"
                                        (into-array Class (repeat n Object)))
        target-type (MethodType/methodType
                     ^Class (case ret-carrier
                              :void Void/TYPE :long Long/TYPE
                              :double Double/TYPE :float Float/TYPE)
                     ^"[Ljava.lang.Class;"
                     (into-array Class (map #(case (carrier %)
                                               :long Long/TYPE
                                               :double Double/TYPE
                                               :float Float/TYPE)
                                            argtypes)))
        mh (-> (MethodHandles/publicLookup)
               (.findVirtual clojure.lang.IFn "invoke" obj-type)
               (.bindTo f)
               (.asType target-type))
        arena (Arena/ofShared)
        stub (.upcallStub ^Linker @linker* mh (descriptor argtypes rettype)
                          arena
                          (make-array java.lang.foreign.Linker$Option 0))
        addr (.address stub)]
    (swap! callback-arenas assoc addr arena)
    addr))

(defn free-callback
  "Releases callback pointer p. C must not call p after this function returns.
  Ignores unknown pointers."
  [p]
  (when-let [^Arena a (get @callback-arenas p)]
    (swap! callback-arenas dissoc p)
    (.close a))
  nil)
