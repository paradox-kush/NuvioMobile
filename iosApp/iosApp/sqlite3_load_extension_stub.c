// ComposeApp's androidx.sqlite cinterop references sqlite3_load_extension, which
// Apple's libsqlite3 does not export — dyld aborts at launch without a definition.
// The loadExtension API is never used; this stub only satisfies the linker/dyld.
int sqlite3_load_extension(void *db, const char *file, const char *proc, char **errmsg) {
    return 1; // SQLITE_ERROR
}
