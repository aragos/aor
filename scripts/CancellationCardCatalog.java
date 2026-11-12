import dev.aragos.aor.CardData;
import dev.aragos.aor.CardDefinition;
import dev.aragos.aor.CardEffectData;
import dev.aragos.aor.CardEffectSpec;
import dev.aragos.aor.Commodity;
import dev.aragos.aor.training.PurchaseJson;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Zero-game static rules export; no game state or outcomes are accepted. */
public final class CancellationCardCatalog {
    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("No game inputs are accepted.");
        Map<String, List<String>> cards = new TreeMap<>();
        for (CardDefinition card : CardData.INSTANCE.getAll()) {
            CardEffectSpec effect = CardEffectData.INSTANCE.forCard(card.getId());
            List<String> choices = effect instanceof CardEffectSpec.CommodityChoices commodity
                ? commodity.getChoices().stream().map(Commodity::name).sorted().toList()
                : List.of();
            cards.put(card.getId(), choices);
        }
        System.out.println(PurchaseJson.INSTANCE.getMapper().writeValueAsString(cards));
    }
}
