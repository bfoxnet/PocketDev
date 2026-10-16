import org.eclipse.tm4e.core.registry.Registry;
import org.eclipse.tm4e.core.registry.IGrammarSource;
import java.io.File;
import java.util.HashMap;

public class TestOrder {
    static String base = "/root/PocketDev/app/src/main/assets/textmate";
    static int next = 2;
    static java.util.Map<String,Integer> ids = new HashMap<>();
    static int id(String s){ Integer i=ids.get(s); if(i==null){i=next++; ids.put(s,i);} return i; }

    static void load(String scope, String file, java.util.Map<String,Integer> embedded) throws Exception {
        Registry r = new Registry();
        r.addGrammar(IGrammarSource.fromFile(new File(base, file)), null, id(scope), embedded);
        System.out.println("OK " + scope);
    }
    public static void main(String[] args) throws Exception {
        // embedded map for vue: scope->grammarId
        java.util.Map<String,Integer> vueEmb = new HashMap<>();
        vueEmb.put("text.html.vue-html", id("text.html.vue-html"));
        vueEmb.put("source.js", id("source.js"));
        load("source.vue", "vue/syntaxes/vue.tmLanguage.json", vueEmb);
        load("text.html.vue-html", "vue-html/syntaxes/vue-html.tmLanguage.json", new HashMap<>());
        load("source.svelte", "svelte/syntaxes/svelte.tmLanguage.json", new HashMap<>());
    }
}
