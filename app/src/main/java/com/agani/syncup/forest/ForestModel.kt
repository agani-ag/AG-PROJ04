package com.agani.syncup.forest

/**
 * The tree kinds of Tools → Forest, as ForestArt draws them. [season] are the months (1–12) when a grown
 * tree flowers or fruits; [fruit] = its season shows fruit instead of flowers.
 */
enum class Species(val title: String, val botanical: String, val season: Set<Int>, val fruit: Boolean = false) {
    NEEM("Neem", "Azadirachta indica", setOf(3, 4)),
    MANGO("Mango", "Mangifera indica", setOf(1, 2, 3, 4, 5, 6)),
    GULMOHAR("Gulmohar", "Delonix regia", setOf(4, 5, 6)),
    PEEPAL("Peepal", "Ficus religiosa", setOf(4, 5, 6), fruit = true),
    COCONUT("Coconut", "Cocos nucifera", setOf(9, 10, 11), fruit = true),
    JASMINE("Jasmine", "Jasminum sambac", setOf(3, 4, 5, 6, 7, 8, 9, 10)),
    BANYAN("Banyan", "Ficus benghalensis", setOf(5, 6, 7), fruit = true),
    BAMBOO("Bamboo", "Bambusa vulgaris", emptySet()),
    ;

    /** Mango flowers Jan–Mar and fruits Apr–Jun. */
    fun fruitsIn(month: Int): Boolean = fruit || (this == MANGO && month in 4..6)
}

/** A tree's stages, seed to grown (today's tree goes through them between sunrise and sunset). */
enum class Stage(val title: String) { SEED("Seed"), SPROUT("Sprout"), SAPLING("Sapling"), YOUNG("Young tree"), GROWN("Grown tree") }
