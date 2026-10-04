package com.marcusrdrigues.noxguard.example.application;

import com.marcusrdrigues.noxguard.history.Turn;
import java.util.List;

/**
 * A visitor's question with the history the client sent back.
 *
 * @param text the question as typed
 * @param history earlier turns; assistant turns carry the signature the server sent
 */
public record Question(String text, List<Turn> history) {}
