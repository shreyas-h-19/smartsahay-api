package com.smartsahay.service;

import com.smartsahay.model.EligibilityRequest;
import com.smartsahay.model.EligibilityResult;
import com.smartsahay.model.Scheme;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure logic for the simulated IVR (keypad + voice demo).
 * No servlet, no JDBC: it only converts keypad codes into an EligibilityRequest
 * and turns results into short sentences that are easy to read aloud.
 */
public class IvrService {

    /** Menu groups, taken from the schemes.level column (ENUM Central/State), so they cannot drift from the database. */
    public static final String[] GROUP_LEVELS = {"Central", "State"};
    private static final String[] GROUP_LABELS = {"Central government schemes", "Karnataka state schemes"};

    /** Schemes shown per page: keys 1..8. Key 9 = more, key 0 = back / repeat. */
    public static final int PAGE_SIZE = 8;

    private static final String[] CATEGORIES = {"GENERAL", "SC", "ST", "OBC", "MINORITY"};
    private static final String[] CATEGORY_LABELS = {"General", "SC", "ST", "OBC", "Minority"};

    /** Income bracket 1..5 -> upper bound in rupees (bracket 5 = "above 8 lakh", sent as 10 lakh). */
    private static final long[] INCOME_UPPER = {100_000, 300_000, 600_000, 800_000, 1_000_000};
    private static final String[] INCOME_LABELS = {
            "Up to ₹1 Lakh", "₹1–3 Lakh", "₹3–6 Lakh", "₹6–8 Lakh", "Above ₹8 Lakh"
    };

    /** Crop key 1..4 -> crop text. 4 ("Other") sends no crop. */
    private static final String[] CROPS = {"KHARIF", "RABI", "MILLET", null};
    private static final String[] CROP_LABELS = {"Kharif", "Rabi", "Millets", "Other"};

    private static final int MAX_SPOKEN_CHARS = 450;

    public static final String BAD_INPUT =
            "Sorry, I could not understand your answers. Please end the call and try again.";

    /** Keypad answers as sent by the browser. fruits and bank are accepted but not used by this backend's rules. */
    public static class Answers {
        private Integer state;    // 1 Karnataka, 2 other
        private Integer age;      // years
        private Integer category; // 1 General, 2 SC, 3 ST, 4 OBC, 5 Minority
        private Integer acres;    // whole acres, 0 = no land
        private Integer income;   // bracket 1..5
        private Integer crop;     // 1 Kharif, 2 Rabi, 3 Millets, 4 Other

        public Integer getState() { return state; }
        public Integer getAge() { return age; }
        public Integer getCategory() { return category; }
        public Integer getAcres() { return acres; }
        public Integer getIncome() { return income; }
        public Integer getCrop() { return crop; }

        public Answers() { }

        public Answers(Integer state, Integer age, Integer category, Integer acres, Integer income, Integer crop) {
            this.state = state;
            this.age = age;
            this.category = category;
            this.acres = acres;
            this.income = income;
            this.crop = crop;
        }
    }

    // ── Dashboard DTOs ──────────────────────────────────────────────────────

    public static class SchemeSummary {
        private final int id;
        private final String name;
        private final String level;
        private final String benefit;
        private final String whereToApply;
        private final boolean spoken;

        public SchemeSummary(int id, String name, String level, String benefit, String whereToApply, boolean spoken) {
            this.id = id;
            this.name = name;
            this.level = level;
            this.benefit = benefit;
            this.whereToApply = whereToApply;
            this.spoken = spoken;
        }

        public int getId() { return id; }
        public String getName() { return name; }
        public String getLevel() { return level; }
        public String getBenefit() { return benefit; }
        public String getWhereToApply() { return whereToApply; }
        public boolean isSpoken() { return spoken; }
    }

    public SchemeSummary summarizeScheme(Scheme s, boolean spoken) {
        if (s == null) return null;
        String benefit = (s.getBenefits() != null && !s.getBenefits().isEmpty())
                ? s.getBenefits().get(0)
                : (s.getDescription() != null ? s.getDescription() : "");
        return new SchemeSummary(
                s.getId(),
                s.getName(),
                s.getLevel(),
                benefit,
                s.getWhereToApply() != null ? s.getWhereToApply() : "",
                spoken
        );
    }

    public List<SchemeSummary> summarizeEligibility(EligibilityResult result) {
        List<SchemeSummary> out = new ArrayList<>();
        if (result == null || result.getEligible() == null) return out;
        List<Scheme> eligible = result.getEligible();
        for (int i = 0; i < eligible.size(); i++) {
            out.add(summarizeScheme(eligible.get(i), i < 3));
        }
        return out;
    }

    public Map<String, String> formatAnswers(Answers a) {
        Map<String, String> map = new LinkedHashMap<>();
        if (a == null) return map;
        if (in(a.state, 1, 2)) map.put("State", a.state == 1 ? "Karnataka" : "Other");
        if (in(a.age, 1, 120)) map.put("Age", a.age + " yrs");
        if (in(a.category, 1, 5)) map.put("Category", CATEGORY_LABELS[a.category - 1]);
        if (in(a.acres, 0, 500)) map.put("Land", a.acres == 0 ? "No land" : a.acres + " acres");
        if (in(a.income, 1, 5)) map.put("Income", INCOME_LABELS[a.income - 1]);
        if (in(a.crop, 1, 4)) map.put("Crop", CROP_LABELS[a.crop - 1]);
        return map;
    }

    // ── Mode 1: scheme information (two-level, database-driven menu) ─────────

    public static class Option {
        private final String key;
        private final String label;
        public Option(String key, String label) { this.key = key; this.label = label; }
        public String getKey() { return key; }
        public String getLabel() { return label; }
    }

    public static class Menu {
        private String text;
        private final List<Option> options;
        private final String level;   // "main" or "list"
        public Menu(String text, List<Option> options, String level) {
            this.text = text;
            this.options = options;
            this.level = level;
        }
        public String getLevel() { return level; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public List<Option> getOptions() { return options; }
    }

    /** Schemes of one group (1 = Central, 2 = State), in the order given (the DAO sorts by id). */
    public List<Scheme> group(List<Scheme> all, int group) {
        List<Scheme> out = new ArrayList<>();
        if (group < 1 || group > GROUP_LEVELS.length) return out;
        for (Scheme s : all) {
            if (GROUP_LEVELS[group - 1].equalsIgnoreCase(s.getLevel())) out.add(s);
        }
        return out;
    }

    /** Top menu: one key per non-empty group. */
    public Menu mainMenu(List<Scheme> all) {
        List<Option> options = new ArrayList<>();
        StringBuilder sb = new StringBuilder("Welcome to Smart Sahay. ");
        for (int g = 1; g <= GROUP_LEVELS.length; g++) {
            if (group(all, g).isEmpty()) continue;
            options.add(new Option(String.valueOf(g), GROUP_LABELS[g - 1]));
            sb.append("For ").append(GROUP_LABELS[g - 1]).append(" press ").append(g).append(". ");
        }
        if (options.isEmpty()) {
            return new Menu("Sorry, no schemes are available right now. Please try again later.", options, "main");
        }
        sb.append("To hear this menu again press 0.");
        return new Menu(toSpeech(sb.toString()), options, "main");
    }

    /** One page of a group's schemes, or null if the group is empty / the page does not exist. */
    public Menu schemeMenu(List<Scheme> all, int group, int page) {
        List<Scheme> list = group(all, group);
        int from = page * PAGE_SIZE;
        if (page < 0 || from >= list.size()) return null;
        int to = Math.min(from + PAGE_SIZE, list.size());

        List<Option> options = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            String key = String.valueOf(i - from + 1);
            options.add(new Option(key, list.get(i).getName()));
            sb.append("For ").append(list.get(i).getName()).append(" press ").append(key).append(". ");
        }
        if (to < list.size()) {
            options.add(new Option("9", "More schemes"));
            sb.append("For more schemes press 9. ");
        }
        sb.append("To return to the main menu press 0.");
        return new Menu(toSpeech(sb.toString()), options, "list");
    }

    /** The scheme behind key 1..8 on a page, or null if the group, page or key is not valid. */
    public Scheme pick(List<Scheme> all, int group, int page, String digits) {
        if (digits == null || digits.length() != 1 || digits.charAt(0) < '1' || digits.charAt(0) > '8') return null;
        List<Scheme> list = group(all, group);
        int index = page * PAGE_SIZE + (digits.charAt(0) - '1');
        if (page < 0 || index >= list.size() || index >= (page + 1) * PAGE_SIZE) return null;
        return list.get(index);
    }

    public String invalidKeySpeech() {
        return "That key is not on the menu.";
    }

    public String schemeNotFoundSpeech() {
        return "Sorry, I could not find that scheme right now. Please try another number.";
    }

    public String schemeSpeech(Scheme s) {
        StringBuilder sb = new StringBuilder();
        sb.append(s.getName()).append(". ");
        if (s.getBenefits() != null && !s.getBenefits().isEmpty()) {
            sb.append(endWithPeriod(s.getBenefits().get(0))).append(' ');
        } else if (s.getDescription() != null) {
            sb.append(endWithPeriod(s.getDescription())).append(' ');
        }
        if (s.getWhereToApply() != null && !s.getWhereToApply().isBlank()) {
            sb.append("You can apply at ").append(endWithPeriod(s.getWhereToApply()));
        }
        return limit(toSpeech(sb.toString().trim()))
                + " Press another number for a different scheme in this list, or press 0 for the main menu.";
    }

    // ── Mode 2: eligibility ─────────────────────────────────────────────────

    /** Returns null when all answers are usable, otherwise a reason (for logs/tests only, never spoken). */
    public String validate(Answers a) {
        if (a == null) return "empty";
        if (!in(a.state, 1, 2)) return "state";
        if (!in(a.age, 1, 120)) return "age";
        if (!in(a.category, 1, 5)) return "category";
        if (!in(a.acres, 0, 500)) return "acres";
        if (!in(a.income, 1, 5)) return "income";
        if (!in(a.crop, 1, 4)) return "crop";
        return null;
    }

    public EligibilityRequest toRequest(Answers a) {
        return EligibilityRequest.of(
                a.getAcres(),
                INCOME_UPPER[a.getIncome() - 1],
                a.getAge(),
                a.getState() == 1 ? "Karnataka" : "Other",
                CATEGORIES[a.getCategory() - 1],
                CROPS[a.getCrop() - 1]);
    }

    public String eligibilitySpeech(EligibilityResult result) {
        int n = result.getEligibleCount();
        if (n == 0) {
            return "Based on your answers, I could not find a matching scheme. "
                    + "Please visit your local agriculture office for more help.";
        }
        List<Scheme> eligible = result.getEligible();
        StringBuilder sb = new StringBuilder("Based on your answers, you may be eligible for ");
        sb.append(n).append(n == 1 ? " scheme" : " schemes").append(". ");
        int shown = Math.min(3, eligible.size());
        sb.append(n > shown ? "They include " : (shown == 1 ? "It is " : "They are "));
        for (int i = 0; i < shown; i++) {
            if (i > 0) sb.append(i == shown - 1 ? " and " : ", ");
            sb.append(eligible.get(i).getName());
        }
        sb.append(". This is only a guide. Please confirm with your local agriculture office.");
        return toSpeech(sb.toString());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    static boolean in(Integer v, int min, int max) {
        return v != null && v >= min && v <= max;
    }

    private static String endWithPeriod(String s) {
        String t = s.trim();
        return (t.endsWith(".") || t.endsWith("!") || t.endsWith("?")) ? t : t + ".";
    }

    /** Make text friendlier for speech synthesis. */
    public static String toSpeech(String text) {
        return text.replace("\u20B9", "rupees ")
                .replace("%", " percent")
                .replace("&", " and ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** Cut at the last full sentence that fits in MAX_SPOKEN_CHARS. */
    static String limit(String text) {
        if (text.length() <= MAX_SPOKEN_CHARS) return text;
        String cut = text.substring(0, MAX_SPOKEN_CHARS);
        int dot = cut.lastIndexOf(". ");
        return dot > 80 ? cut.substring(0, dot + 1) : cut.trim();
    }
}