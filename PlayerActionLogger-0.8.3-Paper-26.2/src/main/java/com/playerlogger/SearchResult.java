package com.playerlogger;

import java.util.List;

public record SearchResult(String player, List<Match> matches) {
    public record Match(int line, String content) {}
}
