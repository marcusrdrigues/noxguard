package com.marcusrdrigues.noxguard.spring.boot;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code noxguard.*} properties. Each part creates its bean only when it is set; a setting that
 * would make a guard unsafe stops the app at startup.
 *
 * <pre>
 * noxguard:
 *   refusal: "I can only answer questions about this site."
 *   stream:
 *     leak-markers: ["&lt;context&gt;", "You are Acme's assistant"]
 *     max-chars: 1200
 *   links:
 *     allow: ['^([a-z0-9-]+\.)*example\.com$']
 *   data:
 *     reserved-tags: [context, question, history]
 *   history:
 *     secret: ${NOXGUARD_HISTORY_SECRET}
 *   tools:
 *     max-calls: 3
 *     allow:
 *       - name: get_case_study
 *         args:
 *           - name: slug
 *             required: true
 *             pattern: '[a-z0-9-]{1,60}'
 *         log-args: [slug]
 *       - name: send_message
 *         confirm: true
 *         max-calls: 1
 * </pre>
 *
 * @param refusal Text that replaces a blocked or empty answer. Required for the ReactorGuard bean.
 * @param stream Stream guard: leak markers and the answer length.
 * @param links Links the answer may contain.
 * @param data Tags that delimit data in the prompt.
 * @param history Signed history.
 * @param tools Tool policy: the tools an agent may call, deny by default.
 */
@ConfigurationProperties("noxguard")
public record NoxguardProperties(String refusal, Stream stream, Links links, Data data, History history, Tools tools) {

    /** Missing parts become empty ones, so the auto-configuration never sees {@code null}. */
    public NoxguardProperties {
        stream = stream == null ? new Stream(null, null) : stream;
        links = links == null ? new Links(null) : links;
        data = data == null ? new Data(null) : data;
        history = history == null ? new History(null, false) : history;
        tools = tools == null ? new Tools(null, null) : tools;
    }

    /**
     * The stream guard, one per answer.
     *
     * @param leakMarkers Comma-separated list of texts that must never reach the user: the start of the
     *     system prompt, data delimiters. Setting them creates the StreamGuards bean.
     * @param maxChars Most characters the user may see. Required with leak markers.
     */
    public record Stream(List<String> leakMarkers, Integer maxChars) {
        /** An absent list is empty. */
        public Stream {
            leakMarkers = leakMarkers == null ? List.of() : List.copyOf(leakMarkers);
        }
    }

    /**
     * The link allow list.
     *
     * @param allow Comma-separated list of regular expressions for the links an answer may contain. They
     *     see lowercase text without the scheme, such as "www.example.com/path". Empty: no link is
     *     allowed.
     */
    public record Links(List<String> allow) {
        /** An absent list is empty. */
        public Links {
            allow = allow == null ? List.of() : List.copyOf(allow);
        }
    }

    /**
     * The data envelope.
     *
     * @param reservedTags Comma-separated list of every tag the prompt uses to delimit data, so none can
     *     be forged from inside a block. Setting them creates the DataEnvelope bean.
     */
    public record Data(List<String> reservedTags) {
        /** An absent list is empty. */
        public Data {
            reservedTags = reservedTags == null ? List.of() : List.copyOf(reservedTags);
        }
    }

    /**
     * The history signer.
     *
     * @param secret HMAC secret, at least 32 bytes; keep it in an environment variable or a secret
     *     manager. Setting it creates the HistorySigner bean; a shorter one stops the app.
     * @param randomSecretForDevelopment Whether to use a random secret when none is set. Only for
     *     development: signed history does not survive a restart, and a warning is logged.
     */
    public record History(String secret, boolean randomSecretForDevelopment) {}

    /**
     * The tool policy.
     *
     * @param maxCalls Most tool calls in one answer, for all tools. Required when tools are declared.
     * @param allow Tools the agent may call; anything else is denied. A list (not a map) so tool names
     *     keep their underscores.
     */
    public record Tools(Integer maxCalls, List<Tool> allow) {
        /** An absent list is empty. */
        public Tools {
            allow = allow == null ? List.of() : List.copyOf(allow);
        }
    }

    /**
     * One allowed tool.
     *
     * @param name Tool name, as the model sends it.
     * @param args Arguments of the tool; any other argument is denied.
     * @param logArgs Comma-separated list of the arguments that may be written to logs.
     * @param confirm Whether the tool has a side effect and needs the user's confirmation before it runs.
     * @param maxCalls Most calls of this tool in one answer.
     */
    public record Tool(String name, List<Arg> args, List<String> logArgs, boolean confirm, Integer maxCalls) {
        /** Absent lists are empty. */
        public Tool {
            args = args == null ? List.of() : List.copyOf(args);
            logArgs = logArgs == null ? List.of() : List.copyOf(logArgs);
        }
    }

    /**
     * One argument of a tool.
     *
     * @param name Argument name.
     * @param required Whether the argument must be present and not null.
     * @param pattern Regular expression the whole value must match. Empty: the argument is allowed with
     *     any value. Rules in code need a ToolPolicy bean of the app's own.
     */
    public record Arg(String name, boolean required, String pattern) {}
}
