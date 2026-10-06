package com.marcusrdrigues.noxguard.agent;

import java.util.Optional;

/** {@link ArgRule#required()}: the session checks it on the absent case, so a present value always passes. */
final class RequiredRule implements ArgRule {

    static final RequiredRule INSTANCE = new RequiredRule();

    private RequiredRule() {}

    @Override
    public Optional<String> check(Object value) {
        return Optional.empty();
    }
}
