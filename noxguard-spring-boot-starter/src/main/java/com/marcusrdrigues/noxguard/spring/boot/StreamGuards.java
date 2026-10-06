package com.marcusrdrigues.noxguard.spring.boot;

import com.marcusrdrigues.noxguard.output.StreamGuard;
import java.util.function.Supplier;

/**
 * Creates the {@link StreamGuard} of each answer. A guard holds the state of one answer, so the bean is
 * this factory, not a guard: call {@link #get()} once per answer.
 *
 * <pre>{@code
 * StreamGuard guard = streamGuards.get();
 * }</pre>
 */
@FunctionalInterface
public interface StreamGuards extends Supplier<StreamGuard> {

    /** A new guard, for one answer. */
    @Override
    StreamGuard get();
}
