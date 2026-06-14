package com.altrix.orchestrator.domain.model.contract;

/**
 * Categories of cross-file inconsistency the {@code ContractValidator}
 * surfaces.  Each kind maps to a specific compile-time failure pattern
 * we have seen the file-by-file migrator produce.
 *
 * <p>The enum is part of the domain so use-cases / persistence /
 * serialisation can switch on it without leaking the JavaParser
 * implementation that detects it.
 */
public enum ContractViolationKind {

    /** A {@code public} type's simple name does not match the file basename. */
    FILE_CLASS_MISMATCH,

    /** An intra-project {@code import} resolves to no declared type in the artifact. */
    UNRESOLVED_INTRA_PROJECT_IMPORT,

    /** A type token (field, parameter, return) is referenced without an import that brings it in. */
    MISSING_IMPORT,

    /**
     * A class declares {@code implements I} but does not provide every
     * abstract method declared on {@code I}.  Catches the "interface drifted,
     * impl didn't follow" pattern.
     */
    MISSING_INTERFACE_METHOD,

    /**
     * A class implements an interface method by NAME but with different
     * parameter/return types than the interface declares — "contract drift".
     * The migrator transformed the same API differently in the interface and
     * the implementation (e.g. {@code publish(String,…)} vs
     * {@code publish(PubsubTopic,…)}).  The interface is authoritative; the
     * implementation must conform.
     */
    INTERFACE_SIGNATURE_MISMATCH,

    /**
     * Call site invokes a method on an intra-project type but no method
     * of that name exists on the receiver.  Catches "caller still uses
     * the old method name after the interface changed".
     */
    UNKNOWN_METHOD_CALL,

    /**
     * {@code new Foo(...)} passes an argument count that matches no
     * constructor declared on {@code Foo}.  Catches "constructor signature
     * changed in one file, callers not updated".
     */
    BAD_CONSTRUCTOR_ARITY,

    /**
     * {@code @Override} is applied to a method whose name + arity does not
     * appear on any super-type the validator can see.
     */
    INVALID_OVERRIDE,

    /**
     * A type referenced cross-package is declared package-private in its
     * source file.  Catches "model leaked the {@code public} modifier
     * while rewriting the class".
     */
    NON_PUBLIC_TYPE_USED_CROSS_PACKAGE
}
