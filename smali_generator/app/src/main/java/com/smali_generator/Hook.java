package com.smali_generator;

/**
 * One patch. Implementations are registered in InitProviderGeneric.hooks and
 * their load() is called once, early, from the injected ContentProvider.
 *
 * load() must never throw: it runs during app startup, and an escaping
 * exception takes the host app down with it. Wrap the body in try/catch and log
 * under the "PATCH" tag.
 */
public interface Hook {

    void load();

    void unload();
}
