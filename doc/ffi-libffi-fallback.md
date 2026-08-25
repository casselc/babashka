# Fixed-signature libffi fallback development note

## Missing capability

The compiled native-image trampoline family could not express arbitrary fixed
signatures or C structs passed and returned by value. The libhegel bindings
that exposed both gaps were `hegel_generate_float` (a wide mixed
integer/floating-point signature), `hegel_string_generator_text`,
`hegel_new_state_machine`, and the by-value `hegel_generate_date`,
`hegel_generate_time`, and `hegel_generate_datetime` calls.

This was not a GraalVM or native-image ABI limitation. The existing FFM path
could perform arbitrary scalar downcalls on the JVM, and the existing
`examples/ffi/libffi.clj` prototype proved that a native image could reach
libffi through already-compiled pointer-only trampolines. The missing piece was
a general binding-time fallback and a declarative struct layout.

## General solution

Fixed scalar signatures continue to use compiled trampolines whenever their
shape is registered. Other fixed signatures, and every signature containing a
`[:struct [[:field type] ...]]` value, build and cache an `ffi_cif` and call
through `ffi_call`. Nested structs are represented as recursive `ffi_type`
values, leaving ABI classification to libffi. Library-scoped symbol lookup is
preserved. No function name or project is special-cased.

Struct arguments are pointers to caller-owned layout buffers. Struct returns
are newly allocated native buffers owned by the caller. Cached CIF/type data
has binding/process lifetime. Per-call scalar and argument-vector storage is
released after each call.

## Independent tests

`babashka.ffi-test` covers:

- C alignment and padding for nested structs;
- struct-by-value arguments and returns;
- a twelve-argument mixed integer, float, and double signature;
- backend routing (`:trampoline`, `:ffm`, and `:libffi`);
- bulk byte and length-delimited UTF-8 copies;
- existing primitive, callback, variadic, and performance canaries.

The C fixture contains no Hegel symbols or dependencies.

## Performance and upstream shape

Supported fixed signatures retain the compiled trampoline path and its
existing performance canary. Only formerly unsupported fixed signatures take
the slower general libffi path. The fallback reuses the existing compiled
`ffi_prep_cif` and `ffi_call` shapes and therefore does not expand the generated
trampoline family.

This is suitable for upstreaming as a general `babashka.ffi` capability.
macOS and typical Linux systems already provide libffi. Minimal Linux images
may need a runtime package; Windows distributions need to provide one of the
documented libffi DLL names. Failure occurs at binding time only when a binding
actually requires the fallback.
