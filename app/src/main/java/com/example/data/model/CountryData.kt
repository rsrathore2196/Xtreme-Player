package com.example.data.model

data class CountryItem(
    val name: String,
    val code: String,
    val flag: String
)

object CountryData {
    val allCountries: List<CountryItem> = listOf(
        CountryItem("India", "IN", "🇮🇳"),
        CountryItem("United States", "US", "🇺🇸"),
        CountryItem("United Kingdom", "GB", "🇬🇧"),
        CountryItem("Canada", "CA", "🇨🇦"),
        CountryItem("Australia", "AU", "🇦🇺"),
        CountryItem("Germany", "DE", "🇩🇪"),
        CountryItem("France", "FR", "🇫🇷"),
        CountryItem("Japan", "JP", "🇯🇵"),
        CountryItem("South Korea", "KR", "🇰🇷"),
        CountryItem("Brazil", "BR", "🇧🇷"),
        CountryItem("Mexico", "MX", "🇲🇽"),
        CountryItem("Spain", "ES", "🇪🇸"),
        CountryItem("Italy", "IT", "🇮🇹"),
        CountryItem("United Arab Emirates", "AE", "🇦🇪"),
        CountryItem("Saudi Arabia", "SA", "🇸🇦"),
        CountryItem("Pakistan", "PK", "🇵🇰"),
        CountryItem("Bangladesh", "BD", "🇧🇩"),
        CountryItem("Nepal", "NP", "🇳🇵"),
        CountryItem("Singapore", "SG", "🇸🇬"),
        CountryItem("Malaysia", "MY", "🇲🇾"),
        CountryItem("Indonesia", "ID", "🇮🇩"),
        CountryItem("Philippines", "PH", "🇵🇭"),
        CountryItem("New Zealand", "NZ", "🇳🇿"),
        CountryItem("South Africa", "ZA", "🇿🇦"),
        CountryItem("Nigeria", "NG", "🇳🇬"),
        CountryItem("Kenya", "KE", "🇰🇪"),
        CountryItem("Egypt", "EG", "🇪🇬"),
        CountryItem("Turkey", "TR", "🇹🇷"),
        CountryItem("Russia", "RU", "🇷🇺"),
        CountryItem("Netherlands", "NL", "🇳🇱"),
        CountryItem("Sweden", "SE", "🇸🇪"),
        CountryItem("Norway", "NO", "🇳🇴"),
        CountryItem("Denmark", "DK", "🇩🇰"),
        CountryItem("Finland", "FI", "🇫🇮"),
        CountryItem("Switzerland", "CH", "🇨🇭"),
        CountryItem("Austria", "AT", "🇦🇹"),
        CountryItem("Belgium", "BE", "🇧🇪"),
        CountryItem("Ireland", "IE", "🇮🇪"),
        CountryItem("Poland", "PL", "🇵🇱"),
        CountryItem("Portugal", "PT", "🇵🇹"),
        CountryItem("Greece", "GR", "🇬🇷"),
        CountryItem("Czech Republic", "CZ", "🇨🇿"),
        CountryItem("Hungary", "HU", "🇭🇺"),
        CountryItem("Romania", "RO", "🇷🇴"),
        CountryItem("Argentina", "AR", "🇦🇷"),
        CountryItem("Colombia", "CO", "🇨🇴"),
        CountryItem("Chile", "CL", "🇨🇱"),
        CountryItem("Peru", "PE", "🇵🇪"),
        CountryItem("Thailand", "TH", "🇹🇭"),
        CountryItem("Vietnam", "VN", "🇻🇳"),
        CountryItem("Sri Lanka", "LK", "🇱🇰"),
        CountryItem("Qatar", "QA", "🇶🇦"),
        CountryItem("Kuwait", "KW", "🇰🇼"),
        CountryItem("Oman", "OM", "🇴🇲"),
        CountryItem("Bahrain", "BH", "🇧🇭"),
        CountryItem("Israel", "IL", "🇮🇱"),
        CountryItem("Morocco", "MA", "🇲🇦"),
        CountryItem("Ghana", "GH", "🇬🇭"),
        CountryItem("Ukraine", "UA", "🇺🇦"),
        CountryItem("Iceland", "IS", "🇮🇸"),
        CountryItem("Croatia", "HR", "🇭🇷"),
        CountryItem("Serbia", "RS", "🇷🇸"),
        CountryItem("Slovakia", "SK", "🇸🇰"),
        CountryItem("Bulgaria", "BG", "🇧🇬"),
        CountryItem("Ecuador", "EC", "🇪🇨"),
        CountryItem("Uruguay", "UY", "🇺🇾"),
        CountryItem("Panama", "PA", "🇵🇦"),
        CountryItem("Costa Rica", "CR", "🇨🇷"),
        CountryItem("Jamaica", "JM", "🇯🇲"),
        CountryItem("Trinidad and Tobago", "TT", "🇹🇹"),
        CountryItem("Mauritius", "MU", "🇲🇺"),
        CountryItem("Fiji", "FJ", "🇫🇯"),
        CountryItem("Maldives", "MV", "🇲🇻")
    )

    fun findCountry(nameOrCode: String): CountryItem? {
        return allCountries.find {
            it.name.equals(nameOrCode, ignoreCase = true) ||
            it.code.equals(nameOrCode, ignoreCase = true)
        }
    }

    /**
     * Maps a country to its authentic regional famous genres, iconic chartbusters, and superstar music queries.
     * Crucially avoids using the country name directly so search engines don't match songs with the country in the title.
     */
    fun getFamousMusicQueriesForCountry(countryName: String): List<String> {
        val lower = countryName.trim().lowercase()
        return when {
            lower.contains("india") -> listOf(
                "Latest Bollywood Hits", "Arijit Singh Hits", "Top Punjabi Pop",
                "Bollywood Top 50", "Sidhu Moose Wala", "Diljit Dosanjh",
                "Shreya Ghoshal Hits", "Trending Hindi Songs", "Anirudh Ravichander"
            )
            lower.contains("united states") || lower == "usa" || lower == "us" -> listOf(
                "Billboard Hot 100", "Top 40 Pop Hits", "Viral Hip Hop Hits",
                "Taylor Swift Hits", "Drake Hits", "Modern R&B Chart", "Morgan Wallen"
            )
            lower.contains("united kingdom") || lower == "uk" || lower.contains("britain") -> listOf(
                "Official UK Top 40", "British Pop Hits", "Ed Sheeran Hits",
                "Dua Lipa Hits", "Coldplay", "Adele Hits", "Harry Styles"
            )
            lower.contains("canada") -> listOf(
                "Canadian Hot 100", "The Weeknd Hits", "Drake Hits",
                "Justin Bieber Pop", "Shawn Mendes Hits"
            )
            lower.contains("australia") -> listOf(
                "ARIA Top 50", "Australian Pop Hits", "Tame Impala",
                "The Kid LAROI", "Troye Sivan", "Dean Lewis"
            )
            lower.contains("pakistan") -> listOf(
                "Coke Studio Hits", "Atif Aslam Hits", "Ali Sethi",
                "Rahat Fateh Ali Khan", "Pakistani Pop Songs", "Nusrat Fateh Ali Khan"
            )
            lower.contains("korea") -> listOf(
                "Melon Top 100", "K-Pop Top Hits", "BTS BLACKPINK Hits",
                "NewJeans K-Pop", "K-Drama OST Hits", "Stray Kids"
            )
            lower.contains("japan") -> listOf(
                "Oricon Top 50", "J-Pop Top Hits", "Yoasobi Hits",
                "Kenshi Yonezu", "Anime Theme Songs", "Ado"
            )
            lower.contains("brazil") -> listOf(
                "Top 50 Brasil", "Funk Carioca Viral", "Sertanejo Universitario",
                "Anitta Hits", "Alok Hits", "Ludmilla"
            )
            lower.contains("mexico") -> listOf(
                "Top 50 Mexico", "Regional Mexicano", "Peso Pluma Hits",
                "Corridos Tumbados", "Christian Nodal", "Natanael Cano"
            )
            lower.contains("spain") -> listOf(
                "Top 50 Espana", "Rosalia Hits", "Quevedo Urban",
                "Latin Pop Viral", "C Tangana", "Aitana"
            )
            lower.contains("germany") -> listOf(
                "Top 100 Germany", "Deutschrap Chart", "Apache 207",
                "Peter Fox", "Eurodance Hits", "German Pop Hits"
            )
            lower.contains("france") -> listOf(
                "Top 50 France", "French Pop Hits", "Aya Nakamura",
                "Stromae Hits", "Jul Rap", "Gims", "Ninho"
            )
            lower.contains("italy") -> listOf(
                "Top 50 Italia", "Italian Pop Hits", "Sanremo Hits",
                "Maneskin", "Sfera Ebbasta", "Annalisa"
            )
            lower.contains("nigeria") -> listOf(
                "Afrobeats Top Hits", "Burna Boy Hits", "Wizkid Hits",
                "Rema Afrobeats", "Asake Hits", "Davido"
            )
            lower.contains("south africa") -> listOf(
                "Amapiano Viral Hits", "Kabza De Small", "Tyla Water",
                "DJ Maphorisa", "South African House"
            )
            lower.contains("jamaica") -> listOf(
                "Reggae Classics", "Dancehall Top Hits", "Bob Marley",
                "Sean Paul Hits", "Koffee Reggae", "Chronixx"
            )
            lower.contains("saudi") || lower.contains("emirates") || lower.contains("uae") ||
            lower.contains("egypt") || lower.contains("qatar") || lower.contains("kuwait") ||
            lower.contains("oman") || lower.contains("bahrain") || lower.contains("morocco") -> listOf(
                "Top Khaleeji Hits", "Arabic Pop Hits", "Amr Diab Hits",
                "Nancy Ajram", "Trending Arabic Songs", "Sherine"
            )
            lower.contains("turkey") -> listOf(
                "Turkce Pop Top 20", "Turkish Pop Hits", "Tarkan Hits",
                "Semicenk", "Turkce Rap Hits", "Mabel Matiz"
            )
            lower.contains("netherlands") -> listOf(
                "Dutch Top 40", "Tiësto Hits", "Martin Garrix",
                "Armin van Buuren", "EDM Festival Hits"
            )
            lower.contains("sweden") || lower.contains("norway") || lower.contains("denmark") ||
            lower.contains("finland") || lower.contains("iceland") -> listOf(
                "Nordic Pop Hits", "Avicii Kygo Hits", "Scandinavian Pop",
                "Zara Larsson", "Euro Pop Hits"
            )
            lower.contains("argentina") || lower.contains("colombia") || lower.contains("chile") ||
            lower.contains("peru") || lower.contains("ecuador") || lower.contains("uruguay") -> listOf(
                "Latin Urban Hits", "Reggaeton Top Hits", "Karol G Hits",
                "Bizarrap Music Sessions", "Feid Hits", "Maluma"
            )
            lower.contains("bangladesh") -> listOf(
                "Top Bangla Hits", "Bangla Pop Songs", "Habib Wahid",
                "Anupam Roy", "Modern Bangla Hits"
            )
            lower.contains("nepal") -> listOf(
                "Top Nepali Songs", "Nepali Pop Hits", "Sajjan Raj Vaidya",
                "Bipul Chettri", "Trending Nepali Hits"
            )
            lower.contains("philippines") -> listOf(
                "OPM Top Hits", "Pinoy Pop Hits", "SB19 Hits",
                "Moira Dela Torre", "Trending OPM Hits"
            )
            lower.contains("indonesia") -> listOf(
                "Indonesian Pop Hits", "Top Indo Pop", "Tulus Hits",
                "Pamungkas", "Dangdut Koplo Viral"
            )
            lower.contains("malaysia") -> listOf(
                "Malay Pop Hits", "Lagu Viral Malaysia", "Faizal Tahir",
                "Siti Nurhaliza Hits"
            )
            lower.contains("thailand") -> listOf(
                "T-Pop Top Hits", "Thai Pop Songs", "Three Man Down",
                "Trending Thai Hits"
            )
            lower.contains("vietnam") -> listOf(
                "V-Pop Top Hits", "Vietnamese Pop", "Son Tung M-TP",
                "Hoang Thuy Linh"
            )
            else -> listOf(
                "Billboard Hot 100", "Viral Pop Hits", "Top Global Chartbusters",
                "Worldwide Dance Pop", "Modern Hit Tracks"
            )
        }
    }

    /**
     * Checks whether a song title contains the literal country name (or common demonyms/forms).
     * Used to prevent recommending songs whose titles just happen to have the country name in them.
     */
    fun hasCountryNameInTitle(title: String, countryName: String): Boolean {
        if (countryName.isBlank()) return false
        val cleanCountry = countryName.trim().lowercase()
        if (cleanCountry.length <= 2) return false
        val cleanTitle = title.lowercase()

        // Direct containment check (e.g. "India", "Canada", "France", "Japan")
        if (cleanTitle.contains(cleanCountry)) return true

        // Check common demonym endings
        if (cleanCountry.endsWith("ia") && cleanTitle.contains(cleanCountry + "n")) return true
        if (cleanCountry.endsWith("a") && cleanTitle.contains(cleanCountry + "n")) return true
        if (cleanCountry.endsWith("ada") && cleanTitle.contains(cleanCountry.dropLast(1) + "ian")) return true
        if (cleanCountry.contains("united states") && (cleanTitle.contains("america") || cleanTitle.contains("american") || cleanTitle.contains("usa"))) return true
        if (cleanCountry.contains("united kingdom") && (cleanTitle.contains("british") || cleanTitle.contains("britain"))) return true

        return false
    }
}
