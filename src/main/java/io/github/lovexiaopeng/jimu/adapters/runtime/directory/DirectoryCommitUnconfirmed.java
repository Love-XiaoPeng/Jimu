package io.github.lovexiaopeng.jimu.adapters.runtime.directory;

/** A directory transaction may have committed; the original slot must decide on a new attempt. */
public final class DirectoryCommitUnconfirmed extends RuntimeException {}
