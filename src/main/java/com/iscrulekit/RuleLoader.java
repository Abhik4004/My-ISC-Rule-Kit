package com.iscrulekit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Reads a rule file (or text) and settles its type: XML attribute, then --type, then folder name, then a guess. */
final class RuleLoader {
    private RuleLoader() {}

    record Loaded(String label, ParsedRule rule, boolean autoType) {}

    static Loaded fromFile(Path file, RuleType forced) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        Path parent = file.toAbsolutePath().getParent();
        RuleType folder = parent == null ? null : RuleType.fromName(parent.getFileName().toString()).orElse(null);
        return fromText(file.toString(), text, forced, folder);
    }

    static Loaded fromText(String label, String text, RuleType forced, RuleType folderHint) {
        ParsedRule p = RuleParser.parse(text, forced);
        boolean auto = false;
        if (p.type == null && !p.typeFromXml) {
            if (forced != null) p.type = forced;
            else if (folderHint != null) p.type = folderHint;
            else {
                Optional<RuleType> g = RuleType.guess(p.xml ? p.source : text);
                p.type = g.orElse(null);
                auto = p.type != null;
            }
        }
        return new Loaded(label, p, auto);
    }
}
