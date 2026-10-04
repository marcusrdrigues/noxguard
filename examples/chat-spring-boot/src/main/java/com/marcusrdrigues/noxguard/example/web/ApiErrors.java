package com.marcusrdrigues.noxguard.example.web;

import com.marcusrdrigues.noxguard.example.application.InvalidQuestionException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns a bad question into a 400 with a short reason. */
@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(InvalidQuestionException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalidQuestion(InvalidQuestionException e) {
        return Map.of("error", e.getMessage());
    }
}
