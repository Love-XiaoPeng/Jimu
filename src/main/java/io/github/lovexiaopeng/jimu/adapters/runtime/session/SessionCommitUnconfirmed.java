package io.github.lovexiaopeng.jimu.adapters.runtime.session;

/** Leave the original slot for lease recovery; do not turn an uncertain commit into a failure. */
public final class SessionCommitUnconfirmed extends RuntimeException {}
