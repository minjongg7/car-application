package com.hmgapps.family.domain

import com.hmgapps.core.trip.GeoMath

/** 지금 지나는 지역 (광역 단위). 퀴즈 문장에 쓰는 이름. */
enum class Region(val label: String) {
    SEOUL("서울"), INCHEON("인천"), GYEONGGI("경기도"), GANGWON("강원도"),
    CHUNGBUK("충청북도"), CHUNGNAM("충청남도"), DAEJEON("대전"), SEJONG("세종"),
    JEONBUK("전라북도"), JEONNAM("전라남도"), GWANGJU("광주"),
    GYEONGBUK("경상북도"), DAEGU("대구"), GYEONGNAM("경상남도"), BUSAN("부산"), ULSAN("울산"),
    JEJU("제주도"),
}

/**
 * 좌표 → 지역. 지역마다 대표 지점(시·군청 부근, 근사값) 몇 개를 두고 가장 가까운 것을 고른다.
 * 경계 근처에서는 이웃 지역으로 나올 수 있다 (퀴즈용이라 충분).
 */
object RegionLocator {
    private val ANCHORS: List<Triple<Region, Double, Double>> = listOf(
        Triple(Region.SEOUL, 37.566, 126.978), Triple(Region.SEOUL, 37.517, 127.047), Triple(Region.SEOUL, 37.654, 127.056),
        Triple(Region.INCHEON, 37.456, 126.705), Triple(Region.INCHEON, 37.449, 126.451),
        Triple(Region.GYEONGGI, 37.263, 127.028), Triple(Region.GYEONGGI, 37.738, 127.034), Triple(Region.GYEONGGI, 37.272, 127.435),
        Triple(Region.GYEONGGI, 36.992, 127.113), Triple(Region.GYEONGGI, 37.394, 127.111), Triple(Region.GYEONGGI, 37.008, 127.280),
        Triple(Region.GANGWON, 37.881, 127.730), Triple(Region.GANGWON, 37.752, 128.876), Triple(Region.GANGWON, 37.342, 127.920),
        Triple(Region.GANGWON, 37.370, 128.390), Triple(Region.GANGWON, 37.164, 128.985),
        Triple(Region.CHUNGBUK, 36.642, 127.489), Triple(Region.CHUNGBUK, 36.991, 127.926), Triple(Region.CHUNGBUK, 37.132, 128.191),
        Triple(Region.CHUNGBUK, 36.175, 127.776), Triple(Region.CHUNGBUK, 36.858, 127.436),
        Triple(Region.CHUNGNAM, 36.601, 126.661), Triple(Region.CHUNGNAM, 36.815, 127.114), Triple(Region.CHUNGNAM, 36.447, 127.119),
        Triple(Region.CHUNGNAM, 36.333, 126.613), Triple(Region.CHUNGNAM, 36.109, 127.488), Triple(Region.CHUNGNAM, 36.785, 126.450),
        Triple(Region.DAEJEON, 36.351, 127.385),
        Triple(Region.SEJONG, 36.480, 127.289),
        Triple(Region.JEONBUK, 35.824, 127.148), Triple(Region.JEONBUK, 35.968, 126.737), Triple(Region.JEONBUK, 35.416, 127.390),
        Triple(Region.JEONBUK, 35.948, 126.958), Triple(Region.JEONBUK, 35.375, 127.137),
        Triple(Region.JEONNAM, 34.812, 126.392), Triple(Region.JEONNAM, 34.760, 127.662), Triple(Region.JEONNAM, 34.951, 127.487),
        Triple(Region.JEONNAM, 35.016, 126.711), Triple(Region.JEONNAM, 34.771, 127.080), Triple(Region.JEONNAM, 35.277, 126.512),
        Triple(Region.GWANGJU, 35.160, 126.852),
        Triple(Region.GYEONGBUK, 36.568, 128.729), Triple(Region.GYEONGBUK, 35.856, 129.225), Triple(Region.GYEONGBUK, 36.019, 129.343),
        Triple(Region.GYEONGBUK, 36.120, 128.344), Triple(Region.GYEONGBUK, 36.587, 128.187), Triple(Region.GYEONGBUK, 35.919, 128.283),
        Triple(Region.GYEONGBUK, 36.000, 128.400), Triple(Region.GYEONGBUK, 36.410, 128.160),
        Triple(Region.DAEGU, 35.871, 128.601),
        Triple(Region.GYEONGNAM, 35.228, 128.681), Triple(Region.GYEONGNAM, 35.180, 128.108), Triple(Region.GYEONGNAM, 34.854, 128.433),
        Triple(Region.GYEONGNAM, 35.504, 128.746), Triple(Region.GYEONGNAM, 35.687, 127.910),
        Triple(Region.BUSAN, 35.180, 129.075), Triple(Region.BUSAN, 35.115, 129.042),
        Triple(Region.ULSAN, 35.539, 129.311),
        Triple(Region.JEJU, 33.499, 126.531), Triple(Region.JEJU, 33.253, 126.560),
    )

    fun locate(lat: Double, lon: Double): Region? {
        val best = ANCHORS.minByOrNull { GeoMath.haversineKm(lat, lon, it.second, it.third) } ?: return null
        // 바다 한가운데·국외처럼 너무 멀면 모른다고 한다
        return if (GeoMath.haversineKm(lat, lon, best.second, best.third) > 80.0) null else best.first
    }
}

/** 지역 퀴즈 한 문제. keywords 중 하나가 대답에 들어 있으면 정답. */
data class RegionQuestion(val region: Region, val question: String, val keywords: List<String>, val explain: String)

/**
 * 지역 특산물·명소 퀴즈. 모든 문장은 이 앱을 위해 새로 쓴 것이다.
 * 사실 확인이 쉬운 대표 항목만 골랐다 (지자체 대표 축제·특산물).
 */
object RegionQuizBank {
    val ALL: List<RegionQuestion> = listOf(
        RegionQuestion(Region.SEOUL, "서울 한가운데를 가로질러 흐르는 큰 강의 이름은?", listOf("한강"), "정답은 한강이에요. 다리가 서른 개 넘게 놓여 있어요."),
        RegionQuestion(Region.SEOUL, "서울에 있는 조선 시대 궁궐 이름을 하나 말해 볼까?", listOf("경복궁", "창덕궁", "창경궁", "덕수궁", "경희궁"), "경복궁, 창덕궁, 창경궁, 덕수궁, 경희궁. 모두 서울에 있는 궁궐이에요."),
        RegionQuestion(Region.INCHEON, "인천에 있는, 비행기가 가장 많이 뜨고 내리는 우리나라 공항은?", listOf("인천공항", "인천국제공항", "공항"), "정답은 인천국제공항이에요."),
        RegionQuestion(Region.GYEONGGI, "지금 경기도 지나는 중! 경기도 이천에서 유명한 곡식은 뭘까?", listOf("쌀", "이천쌀"), "정답은 쌀이에요. 이천 쌀은 옛날 임금님 밥상에도 올랐다고 해요."),
        RegionQuestion(Region.GYEONGGI, "경기도 수원에 있는, 정조 임금 때 쌓은 성의 이름은?", listOf("화성", "수원화성"), "정답은 수원 화성이에요. 유네스코 세계유산이에요."),
        RegionQuestion(Region.GANGWON, "지금 강원도 지나는 중! 강원도 하면 떠오르는, 땅속에서 자라는 동글동글한 작물은?", listOf("감자"), "정답은 감자예요. 강원도는 감자와 옥수수로 유명해요."),
        RegionQuestion(Region.GANGWON, "2018년 겨울 올림픽이 열린 강원도 도시는?", listOf("평창"), "정답은 평창이에요. 강릉과 정선에서도 경기가 열렸어요."),
        RegionQuestion(Region.CHUNGBUK, "지금 충청북도 지나는 중! 충청북도는 우리나라 도 가운데 유일하게 이것이 없어요. 뭘까?", listOf("바다"), "정답은 바다예요. 충청북도는 바다와 맞닿지 않은 유일한 도예요."),
        RegionQuestion(Region.CHUNGBUK, "충청북도 청주에서 만들어진, 금속 활자로 찍은 오래된 책의 이름은?", listOf("직지"), "정답은 직지예요. 현재 남아 있는 금속 활자본 중 가장 오래된 책이에요."),
        RegionQuestion(Region.CHUNGNAM, "지금 충청남도 지나는 중! 충청남도 금산에서 유명한 특산물은?", listOf("인삼", "삼"), "정답은 인삼이에요. 금산은 인삼으로 이름난 고장이에요."),
        RegionQuestion(Region.CHUNGNAM, "충청남도 보령에서 여름마다 열리는, 진흙으로 노는 축제는?", listOf("머드", "진흙"), "정답은 보령 머드축제예요."),
        RegionQuestion(Region.DAEJEON, "대전에서 1993년에 열린, 과학 기술을 보여 주는 큰 박람회는?", listOf("엑스포", "박람회"), "정답은 대전 엑스포예요. 그래서 대전을 과학 도시라고도 불러요."),
        RegionQuestion(Region.SEJONG, "세종시는 어떤 사람 이름을 따서 지었을까?", listOf("세종대왕", "세종"), "정답은 한글을 만든 세종대왕이에요."),
        RegionQuestion(Region.JEONBUK, "지금 전라북도 지나는 중! 전주에서 유명한, 밥에 나물을 올려 비벼 먹는 음식은?", listOf("비빔밥"), "정답은 비빔밥이에요."),
        RegionQuestion(Region.JEONBUK, "전라북도 순창에서 유명한, 빨갛고 매콤한 장은?", listOf("고추장"), "정답은 고추장이에요."),
        RegionQuestion(Region.JEONNAM, "지금 전라남도 지나는 중! 전라남도 보성에서 넓은 밭에 키우는 잎은?", listOf("녹차", "차"), "정답은 녹차예요. 보성 차밭은 초록 계단처럼 생겼어요."),
        RegionQuestion(Region.JEONNAM, "전라남도 영광에서 유명한, 말려서 먹는 생선은?", listOf("굴비", "조기"), "정답은 굴비예요. 조기를 소금에 절여 말린 거예요."),
        RegionQuestion(Region.GWANGJU, "광주를 둘러싼 국립공원 산의 이름은?", listOf("무등산"), "정답은 무등산이에요."),
        RegionQuestion(Region.GYEONGBUK, "지금 경상북도 지나는 중! 경상북도 성주에서 유명한 노란 과일은?", listOf("참외"), "정답은 참외예요."),
        RegionQuestion(Region.GYEONGBUK, "옛날 신라의 수도였던 경상북도 도시는?", listOf("경주"), "정답은 경주예요. 불국사와 첨성대가 있어요."),
        RegionQuestion(Region.DAEGU, "대구는 예전부터 어떤 빨간 과일로 유명했을까?", listOf("사과"), "정답은 사과예요."),
        RegionQuestion(Region.GYEONGNAM, "지금 경상남도 지나는 중! 진주에서 가을에 강 위에 등을 띄우는 축제는?", listOf("유등", "등"), "정답은 진주 남강 유등축제예요."),
        RegionQuestion(Region.BUSAN, "부산에 있는 아주 유명한 해수욕장 이름을 말해 볼까?", listOf("해운대", "광안리", "송정", "다대포"), "해운대, 광안리 같은 바다가 유명해요."),
        RegionQuestion(Region.ULSAN, "울산 장생포에는 어떤 커다란 바다 동물을 주제로 한 마을이 있을까?", listOf("고래"), "정답은 고래예요. 장생포 고래문화마을이 있어요."),
        RegionQuestion(Region.JEJU, "제주도에서 겨울에 많이 나는 주황색 과일은?", listOf("귤", "감귤", "한라봉"), "정답은 감귤이에요."),
        RegionQuestion(Region.JEJU, "제주도 가운데 있는, 우리나라에서 가장 높은 산은?", listOf("한라산"), "정답은 한라산이에요."),
    )

    fun forRegion(r: Region?): List<RegionQuestion> = if (r == null) ALL else ALL.filter { it.region == r }.ifEmpty { ALL }
}
