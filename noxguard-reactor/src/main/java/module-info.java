/**
 * noxguard-reactor: guards a {@code Flux<String>} model stream with the noxguard core.
 */
module com.marcusrdrigues.noxguard.reactor {
    requires transitive com.marcusrdrigues.noxguard;
    requires transitive reactor.core;
    requires transitive org.reactivestreams;

    exports com.marcusrdrigues.noxguard.reactor;
}
