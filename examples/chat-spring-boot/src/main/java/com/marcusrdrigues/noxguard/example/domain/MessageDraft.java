package com.marcusrdrigues.noxguard.example.domain;

/**
 * A message to the store that the model proposed. Nothing is sent: the visitor reviews and confirms.
 *
 * @param subject one line
 * @param message the text
 */
public record MessageDraft(String subject, String message) {}
