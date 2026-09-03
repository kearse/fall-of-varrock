package org.alter.plugins.content.quests.framework

/**
 * Where a quest sits in the journal (design authority 03 §7): the Main Campaign, a Regional
 * Campaign, a strategic problem on the dependency map, or optional/service progression kept
 * apart from the story. Server-side classification today (the custom client journal still lists
 * by chain index); Block-2 briefs set it so the journal can group when the client catches up.
 */
enum class JournalCategory(val display: String) {
    MAIN_CAMPAIGN("Main Campaign"),
    REGIONAL_CAMPAIGN("Regional Campaign"),
    /** A strategic problem / dependency (BREACH · SECURE · UNDERSTAND · SUSTAIN) rather than a quest line. */
    STRATEGIC("Strategic"),
    /** Side roads and service progression — never the journal's "next up". */
    OPTIONAL_SERVICE("Optional & service"),
}
