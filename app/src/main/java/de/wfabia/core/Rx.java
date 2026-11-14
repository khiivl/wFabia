// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.core;

import java.util.regex.Pattern;

/**
 * Regex compile that behaves like Python's Unicode-aware \w \s \b on both runtimes. The desktop JVM needs
 * UNICODE_CHARACTER_CLASS for that; Android's ICU engine is Unicode-aware already and rejects the flag, which made the
 * static initialisers of the renderer fail (NoClassDefFoundError). So the flag is tried and dropped if it is refused.
 */
final class Rx {
    private Rx() {}

    static Pattern compile(String regex, int flags) {
        try {
            return Pattern.compile(regex, flags | Pattern.UNICODE_CHARACTER_CLASS);
        } catch (RuntimeException | LinkageError e) {
            return Pattern.compile(regex, flags);
        }
    }
}
