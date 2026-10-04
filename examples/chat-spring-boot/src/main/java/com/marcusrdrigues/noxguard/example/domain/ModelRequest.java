package com.marcusrdrigues.noxguard.example.domain;

import com.marcusrdrigues.noxguard.history.Turn;
import java.util.List;

/**
 * What goes to the language model.
 *
 * @param system the rules
 * @param history earlier turns, already stripped of forged ones
 * @param user the question with the passages, wrapped as data
 * @param question the visitor's question alone, for models that route on it
 */
public record ModelRequest(String system, List<Turn> history, String user, String question) {}
