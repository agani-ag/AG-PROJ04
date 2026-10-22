package com.agani.syncup.shopping

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BakeryDining
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Cookie
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.Egg
import androidx.compose.material.icons.rounded.LocalDrink
import androidx.compose.material.icons.rounded.RiceBowl
import androidx.compose.material.icons.rounded.Soap
import androidx.compose.ui.graphics.vector.ImageVector

/** Where an item sits in the shop. Items in a list are grouped under these, in this order. */
internal enum class Aisle(val id: String, val title: String, val short: String) {
    PRODUCE("produce", "Fruits & vegetables", "Fruits & veg"),
    DAIRY("dairy", "Dairy & eggs", "Dairy & eggs"),
    BAKERY("bakery", "Bakery", "Bakery"),
    STAPLES("staples", "Grains & staples", "Grains & staples"),
    SNACKS("snacks", "Snacks", "Snacks"),
    DRINKS("drinks", "Drinks", "Drinks"),
    HOUSEHOLD("household", "Household", "Household"),
    PERSONAL("personal", "Personal care", "Personal care"),
    OTHER("other", "Other", "Other"),
    ;

    val icon: ImageVector
        get() = when (this) {
            PRODUCE -> Icons.Rounded.Eco
            DAIRY -> Icons.Rounded.Egg
            BAKERY -> Icons.Rounded.BakeryDining
            STAPLES -> Icons.Rounded.RiceBowl
            SNACKS -> Icons.Rounded.Cookie
            DRINKS -> Icons.Rounded.LocalDrink
            HOUSEHOLD -> Icons.Rounded.CleaningServices
            PERSONAL -> Icons.Rounded.Soap
            OTHER -> Icons.Rounded.Category
        }

    companion object {
        fun of(id: String): Aisle = entries.firstOrNull { it.id == id } ?: OTHER
    }
}

/**
 * Picks the aisle from an item's name, offline, from a word list (English and common Indian grocery
 * words). Phrases first ("dish soap", "peanut butter"); then the words from the last one back, since
 * the last word usually says what it is ("potato chips" → Snacks, "apple juice" → Drinks).
 */
internal object Aisles {
    private val words: Map<String, Aisle> = buildMap {
        fun put(aisle: Aisle, list: String) = list.split(' ', '\n').filter { it.isNotBlank() }.forEach { put(it, aisle) }
        put(
            Aisle.PRODUCE,
            """tomato onion potato garlic ginger chilli chili chillies mirchi capsicum carrot bean beans pea peas cabbage
            cauliflower gobi broccoli spinach palak methi coriander dhania cilantro mint pudina cucumber brinjal baingan
            eggplant okra bhindi ladyfinger beetroot beet radish mooli pumpkin gourd lauki karela bittergourd drumstick
            mushroom corn lettuce lemon lime nimbu banana apple mango orange grape papaya guava pomegranate anar watermelon
            melon muskmelon pineapple strawberry berry blueberry kiwi pear peach plum cherry chikoo sapota coconut avocado
            fruit vegetable veg veggie veggies sabzi sabji salad shallot leek celery zucchini aloo pyaz pyaaz tamatar
            amla jackfruit plantain yam arbi colocasia tindora parwal tinda turnip sprouts""",
        )
        put(
            Aisle.DAIRY,
            """milk curd dahi yogurt yoghurt paneer ghee butter cheese cream egg buttermilk chaas khoa khova mozzarella
            cheddar dairy""",
        )
        put(
            Aisle.BAKERY,
            """bread bun buns pav pao cake rusk toast croissant muffin bagel pastry brownie doughnut donut khari baguette
            loaf cupcake""",
        )
        put(
            Aisle.STAPLES,
            """rice atta maida flour sooji suji rava rawa besan dal daal dhal lentil toor tuvar arhar moong mung masoor urad
            chana channa rajma chole kabuli poha oats sugar jaggery gur salt oil masala spice spices turmeric haldi jeera
            cumin mustard rai pepper hing asafoetida cardamom elaichi clove laung cinnamon dalchini tamarind imli pasta
            noodle noodles maggi vermicelli semiya sevai cereal cornflakes muesli honey jam ketchup sauce vinegar pickle
            achar papad cashew kaju almond badam raisin kishmish walnut pista pistachio dates wheat millet ragi bajra jowar
            quinoa sabudana dalia daliya grain grains ajwain saunf fenugreek kasuri peanut moongphali groundnut sesame til
            garam""",
        )
        put(
            Aisle.SNACKS,
            """biscuit biscuits cookie cookies chips namkeen bhujia mixture chocolate chocolates candy toffee sweets mithai
            chikki popcorn nachos wafer wafers crackers kurkure lays snack snacks ladoo laddu halwa icecream""",
        )
        put(
            Aisle.DRINKS,
            """tea chai coffee juice soda cola coke pepsi sprite fanta limca thumsup water drink drinks squash sharbat
            horlicks bournvita boost complan lemonade beer wine milkshake rasna frooti maaza tang""",
        )
        put(
            Aisle.HOUSEHOLD,
            """detergent surf ariel tide rin nirma dishwash vim pril cleaner phenyl harpic lizol colin bleach broom jhadu
            mop sponge scrub scrubber foil tissue tissues napkin napkins battery batteries bulb candle candles matchbox
            matches agarbatti incense mosquito repellent freshener softener comfort starch naphthalene gloves bucket
            garbage dustbin cling zipper""",
        )
        put(
            Aisle.PERSONAL,
            """soap shampoo conditioner toothpaste toothbrush facewash handwash bodywash deodorant deo perfume lotion
            moisturiser moisturizer sunscreen razor blade blades shaving sanitary pad pads diaper diapers comb floss
            mouthwash cotton bandage bandaid medicine tablet tablets vaseline talc talcum kajal lipstick sanitizer
            sanitiser wipes shaver trimmer""",
        )
    }

    private val phrases: List<Pair<String, Aisle>> = listOf(
        "dish soap" to Aisle.HOUSEHOLD, "dish wash" to Aisle.HOUSEHOLD, "dishwasher" to Aisle.HOUSEHOLD,
        "washing powder" to Aisle.HOUSEHOLD, "toilet paper" to Aisle.HOUSEHOLD, "toilet cleaner" to Aisle.HOUSEHOLD,
        "floor cleaner" to Aisle.HOUSEHOLD, "kitchen towel" to Aisle.HOUSEHOLD, "garbage bag" to Aisle.HOUSEHOLD,
        "trash bag" to Aisle.HOUSEHOLD, "air freshener" to Aisle.HOUSEHOLD, "fabric softener" to Aisle.HOUSEHOLD,
        "all out" to Aisle.HOUSEHOLD, "good knight" to Aisle.HOUSEHOLD, "aluminium foil" to Aisle.HOUSEHOLD,
        "hand wash" to Aisle.PERSONAL, "face wash" to Aisle.PERSONAL, "body wash" to Aisle.PERSONAL,
        "hair oil" to Aisle.PERSONAL, "face cream" to Aisle.PERSONAL,
        "cold cream" to Aisle.PERSONAL, "shaving cream" to Aisle.PERSONAL, "body lotion" to Aisle.PERSONAL,
        "tooth paste" to Aisle.PERSONAL, "tooth brush" to Aisle.PERSONAL, "lip balm" to Aisle.PERSONAL,
        "band aid" to Aisle.PERSONAL, "talcum powder" to Aisle.PERSONAL, "baby powder" to Aisle.PERSONAL,
        "ice cream" to Aisle.SNACKS, "potato chips" to Aisle.SNACKS, "banana chips" to Aisle.SNACKS,
        "peanut butter" to Aisle.STAPLES, "chilli powder" to Aisle.STAPLES, "chili powder" to Aisle.STAPLES,
        "coriander powder" to Aisle.STAPLES, "garam masala" to Aisle.STAPLES, "curry leaves" to Aisle.PRODUCE,
        "coconut milk" to Aisle.STAPLES, "milk powder" to Aisle.DAIRY, "bread crumbs" to Aisle.STAPLES,
        "coconut water" to Aisle.DRINKS, "tender coconut" to Aisle.DRINKS, "soft drink" to Aisle.DRINKS,
        "cold drink" to Aisle.DRINKS, "green tea" to Aisle.DRINKS, "energy drink" to Aisle.DRINKS,
        "sweet potato" to Aisle.PRODUCE, "spring onion" to Aisle.PRODUCE, "lady finger" to Aisle.PRODUCE,
        "bitter gourd" to Aisle.PRODUCE, "bottle gourd" to Aisle.PRODUCE, "green chilli" to Aisle.PRODUCE,
        "bell pepper" to Aisle.PRODUCE, "garlic paste" to Aisle.STAPLES, "tomato paste" to Aisle.STAPLES,
        "pizza base" to Aisle.BAKERY, "brown bread" to Aisle.BAKERY,
    ).sortedByDescending { it.first.length }

    fun guess(name: String): Aisle {
        val clean = " " + name.lowercase().replace(Regex("[^a-z]+"), " ").trim() + " "
        if (clean.isBlank()) return Aisle.OTHER
        phrases.firstOrNull { clean.contains(" ${it.first} ") }?.let { return it.second }
        val parts = clean.trim().split(' ').filter { it.isNotEmpty() }
        for (w in parts.asReversed()) lookup(w)?.let { return it }
        return Aisle.OTHER
    }

    /** The word, or its singular ("tomatoes" → "tomato", "berries" → "berry"). */
    private fun lookup(w: String): Aisle? {
        words[w]?.let { return it }
        if (w.length > 3 && w.endsWith("ies")) words[w.dropLast(3) + "y"]?.let { return it }
        if (w.length > 3 && w.endsWith("es")) words[w.dropLast(2)]?.let { return it }
        if (w.length > 2 && w.endsWith("s")) words[w.dropLast(1)]?.let { return it }
        return null
    }
}
