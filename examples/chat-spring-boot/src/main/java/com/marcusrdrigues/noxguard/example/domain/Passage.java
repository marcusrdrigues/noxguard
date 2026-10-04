package com.marcusrdrigues.noxguard.example.domain;

/**
 * A piece of the store's published content, found for a question.
 *
 * @param source where it comes from, such as "hours"
 * @param text the content
 */
public record Passage(String source, String text) {}
