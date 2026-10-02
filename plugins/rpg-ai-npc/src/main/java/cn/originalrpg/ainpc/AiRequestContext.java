package cn.originalrpg.ainpc;

record AiRequestContext(
        String locale,
        String profileSummary,
        String history,
        String question
) {
    String userPrompt() {
        String language = locale.equals("en_us") ? "English" : "Simplified Chinese";
        return "Response language: " + language + "\n"
                + "Read-only player snapshot: " + profileSummary + "\n"
                + (history.isBlank() ? "" : "Recent dialogue:\n" + history + "\n")
                + "Player question: " + question;
    }
}
