package com.iscrulekit;

/** Implemented by mock ISC objects so test reports and assertions can see their state as plain maps/lists. */
public interface PlainConvertible {
    Object toPlain();
}
