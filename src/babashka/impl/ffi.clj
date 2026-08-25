(ns babashka.impl.ffi
  {:no-doc true}
  (:require [babashka.ffi :as ffi]
            [sci.core :as sci :refer [copy-var]]))

(def tns (sci/create-ns 'babashka.ffi nil))

(def ffi-namespace
  {'load-library (copy-var ffi/load-library tns)
   'load-system-library (copy-var ffi/load-system-library tns)
   'find-symbol (copy-var ffi/find-symbol tns)
   'signature-backend (copy-var ffi/signature-backend tns)
   'cfn (copy-var ffi/cfn tns)
   'defcfn (copy-var ffi/defcfn tns)
   'alloc (copy-var ffi/alloc tns)
   'free (copy-var ffi/free tns)
   'sizeof (copy-var ffi/sizeof tns)
   'layout (copy-var ffi/layout tns)
   'layout-size (copy-var ffi/layout-size tns)
   'read (copy-var ffi/read tns)
   'write (copy-var ffi/write tns)
   'read-field (copy-var ffi/read-field tns)
   'write-field (copy-var ffi/write-field tns)
   'read-array (copy-var ffi/read-array tns)
   'write-array (copy-var ffi/write-array tns)
   'read-bytes (copy-var ffi/read-bytes tns)
   'write-bytes (copy-var ffi/write-bytes tns)
   'ptr->string (copy-var ffi/ptr->string tns)
   'string->ptr (copy-var ffi/string->ptr tns)
   'null (copy-var ffi/null tns)
   'null? (copy-var ffi/null? tns)
   'callback (copy-var ffi/callback tns)
   'free-callback (copy-var ffi/free-callback tns)})
