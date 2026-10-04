package com.marcusrdrigues.noxguard.example.application;

/** The question is empty or too long. The web layer turns it into a 400. */
public class InvalidQuestionException extends RuntimeException {

    public InvalidQuestionException(String message) {
        super(message);
    }
}
