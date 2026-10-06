package com.marcusrdrigues.noxguard.example.infrastructure;

import com.marcusrdrigues.noxguard.agent.ToolCall;
import com.marcusrdrigues.noxguard.example.domain.StoreTools;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The store's read-only tool, {@code check_stock}, over a small made-up catalog. The tool policy
 * ({@code noxguard.tools} in {@code application.yml}) checked the title before this runs.
 */
@Component
public class ExampleBooksTools implements StoreTools {

    private static final Map<String, Integer> STOCK = Map.of(
            "dune", 3,
            "the hobbit", 0,
            "pride and prejudice", 5);

    @Override
    public String run(ToolCall call) {
        if (!"check_stock".equals(call.name())) {
            // The policy runs only declared tools, and this is the only read-only one.
            throw new IllegalArgumentException("not a store tool: " + call.name());
        }
        String title = (String) call.args().get("title");
        Integer copies = STOCK.get(title.toLowerCase(Locale.ROOT).strip());
        if (copies == null) {
            return "Not in the catalog. Books not in stock can be ordered.";
        }
        return copies > 0 ? "In stock: " + copies + " copies." : "Out of stock; it can be ordered and arrives in 3 to 5 business days.";
    }
}
