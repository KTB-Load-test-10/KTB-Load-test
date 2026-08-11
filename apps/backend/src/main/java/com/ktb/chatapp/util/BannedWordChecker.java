package com.ktb.chatapp.util;

import java.util.Locale;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.util.Assert;

public class BannedWordChecker {
    
    private final Set<String> bannedWords;
    private final Node root = new Node();
    
    public BannedWordChecker(Set<String> bannedWords) {
        this.bannedWords =
                bannedWords.stream()
                        .filter(word -> word != null && !word.isBlank())
                        .map(word -> word.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
        Assert.notEmpty(this.bannedWords, "Banned words set must not be empty");
        buildMatcher();
    }
    
    public boolean containsBannedWord(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        
        String normalizedMessage = message.toLowerCase(Locale.ROOT);
        Node state = root;
        for (int i = 0; i < normalizedMessage.length(); i++) {
            char current = normalizedMessage.charAt(i);
            while (state != root && !state.children.containsKey(current)) {
                state = state.failure;
            }
            state = state.children.getOrDefault(current, root);
            if (state.terminal) {
                return true;
            }
        }
        return false;
    }

    /** 사전은 시작 시 한 번만 Aho-Corasick 상태 기계로 변환한다. */
    private void buildMatcher() {
        for (String word : bannedWords) {
            Node node = root;
            for (int i = 0; i < word.length(); i++) {
                node = node.children.computeIfAbsent(word.charAt(i), ignored -> new Node());
            }
            node.terminal = true;
        }

        root.failure = root;
        Queue<Node> queue = new ArrayDeque<>();
        for (Node child : root.children.values()) {
            child.failure = root;
            queue.add(child);
        }
        while (!queue.isEmpty()) {
            Node parent = queue.remove();
            for (Map.Entry<Character, Node> entry : parent.children.entrySet()) {
                char edge = entry.getKey();
                Node child = entry.getValue();
                Node fallback = parent.failure;
                while (fallback != root && !fallback.children.containsKey(edge)) {
                    fallback = fallback.failure;
                }
                if (fallback.children.containsKey(edge) && fallback.children.get(edge) != child) {
                    fallback = fallback.children.get(edge);
                }
                child.failure = fallback;
                child.terminal = child.terminal || fallback.terminal;
                queue.add(child);
            }
        }
    }

    private static final class Node {
        private final Map<Character, Node> children = new HashMap<>();
        private Node failure;
        private boolean terminal;
    }
}
