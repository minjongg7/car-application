package com.hmgapps.family.domain

/**
 * 패밀리 & 동승자 케어: 나이대별 콘텐츠.
 * 모든 이야기·퀴즈·놀이 문장은 이 앱을 위해 새로 쓴 것이다 (기존 동화·동요 가사 사용 없음 → 저작권 문제 없음).
 * 재생은 TTS 로 하므로 주행 중에도 화면을 보지 않고 들을 수 있다.
 */
enum class AgeGroup(val label: String, val range: String, val emoji: String, val restEveryMin: Int) {
    // restEveryMin: 앱이 제안하는 휴식 간격(분). 어릴수록 짧게 잡은 앱 기본값이며 의학적 기준이 아니다.
    TODDLER("영유아", "0–3세", "🍼", 90), // 카시트에 오래 앉지 않도록 1.5~2시간마다
    KID("유아", "4–7세", "🧸", 90),
    CHILD("초등", "8–12세", "🎒", 120),
    TEEN("청소년", "13–18세", "🎧", 120),
    ADULT("어른·어르신", "19세+", "☕", 120),
}

enum class ContentType(val label: String, val emoji: String) {
    STORY("음성 동화", "📖"),
    QUIZ("퀴즈", "❓"),
    GAME("창밖 놀이", "🚗"),
    CALM("잠·진정", "🌙"),
    LEARN("여행 상식", "🗺"),
}

/** 퀴즈 한 문제. 문제를 읽고, '정답'을 누르면 답과 설명을 읽는다. */
data class QuizItem(val question: String, val answer: String)

data class FamilyContent(
    val id: String,
    val title: String,
    val type: ContentType,
    val ages: Set<AgeGroup>,
    val minutes: Int,
    /** TTS 로 차례대로 읽을 문장들 (STORY / CALM / LEARN / GAME 안내). */
    val lines: List<String> = emptyList(),
    /** QUIZ 전용. */
    val quiz: List<QuizItem> = emptyList(),
    /** GAME(창밖 빙고) 전용: 찾을 것들. */
    val bingo: List<String> = emptyList(),
)

object FamilyCatalog {
    private val ALL_KIDS = setOf(AgeGroup.KID, AgeGroup.CHILD)
    private val EVERYONE = AgeGroup.entries.toSet()

    val items: List<FamilyContent> = listOf(
        // ---------- 영유아 (0–3): 짧고 반복되는 소리, 잠·진정 위주
        FamilyContent(
            "t-sound", "부릉부릉 소리 친구", ContentType.STORY, setOf(AgeGroup.TODDLER), 2,
            lines = listOf(
                "자동차는 부릉부릉.", "빗방울은 똑똑똑.", "바람은 쉬이이.",
                "엄마 아빠랑 함께 가요. 부릉부릉.", "창밖의 나무가 안녕 하고 손을 흔들어요.", "우리도 안녕!",
            ),
        ),
        FamilyContent(
            "t-sleep", "구름 이불 자장 이야기", ContentType.CALM, setOf(AgeGroup.TODDLER, AgeGroup.KID), 4,
            lines = listOf(
                "하늘에 폭신한 구름 이불이 있어요.", "구름 이불이 천천히, 천천히 내려와요.",
                "발끝을 덮고, 무릎을 덮고, 배를 덮어요.", "숨을 크게 들이쉬고… 후우 내쉬어요.",
                "자동차가 살랑살랑 흔들흔들 요람처럼 흔들려요.", "눈을 감으면 구름 위를 둥실둥실 날아요.", "잘 자요, 우리 아기.",
            ),
        ),

        // ---------- 유아 (4–7): 이야기 + 쉬운 말놀이 + 색깔 찾기
        FamilyContent(
            "k-story", "꼬마 전기차 반짝이의 첫 여행", ContentType.STORY, setOf(AgeGroup.KID, AgeGroup.TODDLER), 4,
            lines = listOf(
                "작은 전기차 반짝이는 오늘 처음으로 멀리 여행을 가요.",
                "반짝이는 배가 고프면 기름 대신 전기를 먹어요. 꿀꺽꿀꺽, 충전 완료!",
                "고속도로에 올라가니 커다란 트럭 아저씨가 인사했어요. 빵빵, 안전하게 가렴!",
                "반짝이는 오래 달리면 쉬어 가야 한다는 걸 알아요. 그래서 휴게소에 들렀지요.",
                "휴게소에서 반짝이는 친구 버스를 만났어요. 너도 쉬러 왔구나!",
                "다시 출발! 반짝이는 차선을 잘 지키고 천천히, 안전하게 달렸어요.",
                "드디어 도착! 반짝이가 말했어요. 함께 와 줘서 고마워. 다음 여행도 같이 가자!",
            ),
        ),
        FamilyContent(
            "k-color", "색깔 자동차 찾기", ContentType.GAME, setOf(AgeGroup.KID, AgeGroup.TODDLER), 5,
            lines = listOf("창밖에서 이 색깔 자동차를 찾아 볼까요? 찾으면 화면의 칸을 눌러 주세요."),
            bingo = listOf("빨간 차", "파란 차", "하얀 차", "검은 차", "노란 버스", "초록 트럭", "은색 차", "주황 차", "택시"),
        ),
        FamilyContent(
            "k-quiz", "동물 소리 퀴즈", ContentType.QUIZ, setOf(AgeGroup.KID, AgeGroup.TODDLER), 3,
            quiz = listOf(
                QuizItem("음메에 하고 우는 동물은 누구일까요?", "정답은 소예요. 우유를 주는 고마운 친구지요."),
                QuizItem("꽥꽥 소리를 내며 물 위를 떠다니는 동물은?", "정답은 오리예요."),
                QuizItem("야옹 하고 우는 동물은?", "정답은 고양이예요."),
                QuizItem("멍멍 짖으며 꼬리를 흔드는 동물은?", "정답은 강아지예요."),
                QuizItem("어흥 하고 우는 숲 속의 왕은?", "정답은 호랑이예요."),
            ),
        ),

        // ---------- 초등 (8–12): 퀴즈·빙고·과학 상식
        FamilyContent(
            "c-bingo", "고속도로 빙고", ContentType.GAME, setOf(AgeGroup.CHILD, AgeGroup.KID), 10,
            lines = listOf("창밖에서 아래 9가지를 찾아 보세요. 한 줄을 다 채우면 빙고!"),
            bingo = listOf("터널", "휴게소 표지판", "다리", "산", "강이나 호수", "전기차 충전소", "공사 구간 표지판", "트럭 세 대", "졸음쉼터 표지판"),
        ),
        FamilyContent(
            "c-quiz", "자동차 과학 퀴즈", ContentType.QUIZ, setOf(AgeGroup.CHILD, AgeGroup.TEEN), 5,
            quiz = listOf(
                QuizItem("전기차가 내리막길에서 속도를 줄일 때 배터리를 다시 채우는 기술을 무엇이라고 할까요?", "정답은 회생 제동이에요. 브레이크를 밟을 때 생기는 힘을 전기로 바꿔 배터리에 저장해요."),
                QuizItem("터널 안에서는 왜 전조등을 켜야 할까요?", "어두운 곳에서 내 차가 잘 보이게 하고, 앞을 잘 보기 위해서예요."),
                QuizItem("고속도로에서 가장 바깥쪽 끝 차로 옆, 비상시에만 쓰는 곳의 이름은?", "정답은 갓길이에요. 고장이나 사고 같은 비상 상황에만 쓸 수 있어요."),
                QuizItem("자동차 안에서 안전벨트는 어린이도 꼭 매야 할까요?", "네, 모든 좌석에서 꼭 매야 해요. 어린이는 키에 맞는 카시트나 부스터 시트를 함께 써요."),
                QuizItem("전기차 배터리가 추운 겨울에 더 빨리 닳는 이유는?", "배터리는 차가우면 화학 반응이 느려지고, 히터에도 전기를 쓰기 때문이에요."),
            ),
        ),
        FamilyContent(
            "c-word", "끝말잇기 시작 단어", ContentType.QUIZ, setOf(AgeGroup.CHILD, AgeGroup.KID, AgeGroup.ADULT), 5,
            quiz = listOf(
                QuizItem("자동차! 자동차의 '차'로 시작하는 말을 이어 볼까요?", "예를 들면 차례, 차표, 차선! 다음 사람이 이어서 말해요."),
                QuizItem("고속도로! '로'로 시작하는 말은?", "예를 들면 로봇, 로켓! 계속 이어 가요."),
                QuizItem("휴게소! '소'로 시작하는 말은?", "예를 들면 소나무, 소풍, 소방차!"),
            ),
        ),

        // ---------- 청소년 (13–18): 상식·토론 거리·집중 음성
        FamilyContent(
            "y-learn", "지나가는 길의 지리 이야기", ContentType.LEARN, setOf(AgeGroup.TEEN, AgeGroup.CHILD, AgeGroup.ADULT), 4,
            lines = listOf(
                "우리나라는 국토의 약 70퍼센트가 산지라서, 고속도로에 터널과 다리가 많아요.",
                "그래서 긴 터널 안에는 일정한 간격으로 비상 대피 통로와 소화기가 설치되어 있어요.",
                "고속도로 휴게소는 운전자가 쉬어 갈 수 있도록 일정한 간격마다 만들어져 있어요.",
                "최근에는 휴게소마다 전기차 급속 충전기가 늘어나서, 장거리 전기차 여행도 쉬워졌어요.",
                "다음 휴게소에 도착하면 그 지역의 특산물이 무엇인지 찾아 보는 것도 재미있겠죠?",
            ),
        ),
        FamilyContent(
            "y-quiz", "모빌리티 미래 퀴즈", ContentType.QUIZ, setOf(AgeGroup.TEEN, AgeGroup.ADULT), 5,
            quiz = listOf(
                QuizItem("차량 소프트웨어를 무선으로 업데이트하는 기술을 영어 약자로 무엇이라고 할까요?", "정답은 OTA, Over The Air예요. 스마트폰처럼 차도 무선으로 업데이트돼요."),
                QuizItem("소프트웨어가 차량의 기능을 정의하는 자동차를 무엇이라고 부를까요?", "정답은 SDV, 소프트웨어 중심 자동차예요."),
                QuizItem("전기차 배터리로 집이나 캠핑 장비에 전기를 공급하는 기능은?", "정답은 V2L, Vehicle to Load예요."),
                QuizItem("전기차 배터리의 전기를 전력망으로 되팔 수 있는 기술은?", "정답은 V2G, Vehicle to Grid예요."),
            ),
        ),
        FamilyContent(
            "y-calm", "3분 호흡 리셋", ContentType.CALM, setOf(AgeGroup.TEEN, AgeGroup.ADULT, AgeGroup.CHILD), 3,
            lines = listOf(
                "편하게 앉아서 어깨에 힘을 빼 볼게요.", "코로 넷을 세며 숨을 들이쉬세요. 하나, 둘, 셋, 넷.",
                "잠깐 멈추고… 입으로 여섯을 세며 천천히 내쉬세요.", "한 번 더. 들이쉬고… 천천히 내쉬고.",
                "멀미가 날 땐 먼 곳의 수평선이나 산을 바라보면 도움이 돼요.", "잘했어요. 이제 남은 여행을 편하게 즐겨요.",
            ),
        ),

        // ---------- 어른·어르신: 대화 거리, 가족 퀴즈, 진정
        FamilyContent(
            "a-talk", "가족 대화 카드", ContentType.QUIZ, setOf(AgeGroup.ADULT, AgeGroup.TEEN, AgeGroup.CHILD), 10,
            quiz = listOf(
                QuizItem("이번 여행에서 가장 기대되는 한 가지는 무엇인가요?", "돌아가며 한 명씩 이야기해 보세요."),
                QuizItem("가족과 함께한 여행 중 가장 기억에 남는 순간은?", "어른이 먼저 이야기를 들려주면 아이들도 쉽게 이어 가요."),
                QuizItem("도착하면 제일 먼저 먹고 싶은 음식은?", "모두의 답을 모아 첫 끼 메뉴를 정해 보세요."),
                QuizItem("어릴 때 할머니, 할아버지와 가장 좋아했던 놀이는?", "어르신께 여쭤보면 재미있는 옛날 이야기를 들을 수 있어요."),
            ),
        ),
        FamilyContent(
            "a-learn", "장거리 운전 동승자 팁", ContentType.LEARN, setOf(AgeGroup.ADULT), 3,
            lines = listOf(
                "동승자는 운전자의 두 번째 눈이에요. 졸음이 보이면 먼저 휴식을 권해 주세요.",
                "아이와 함께라면 1시간에서 2시간마다 휴게소에 들러 몸을 풀어 주는 게 좋아요.",
                "주행 중에는 운전자에게 화면 조작을 부탁하지 말고, 동승자가 대신 해 주세요.",
                "물과 간식은 손이 닿는 곳에 두면 갑자기 멈추지 않아도 돼요.",
            ),
        ),
        FamilyContent(
            "e-all", "다 함께 수수께끼", ContentType.QUIZ, EVERYONE - AgeGroup.TODDLER, 5,
            quiz = listOf(
                QuizItem("발은 없는데 하루 종일 길을 달리는 것은?", "정답은 자동차 바퀴! 사실 우리가 탄 자동차 전체이기도 해요."),
                QuizItem("들어가면 깜깜하고 나오면 환해지는 것은?", "정답은 터널이에요."),
                QuizItem("배가 고프면 꽂아 주고, 배가 부르면 빼는 것은?", "정답은 전기차 충전기예요."),
            ),
        ),
    )

    /** 선택한 탑승자 나이대 중 하나라도 맞는 콘텐츠. 여러 나이대에 맞을수록(함께 즐기기 좋은 것) 앞으로. */
    fun forAges(ages: Set<AgeGroup>): List<FamilyContent> {
        if (ages.isEmpty()) return items
        return items.filter { it.ages.any(ages::contains) }
            .sortedByDescending { c -> c.ages.count(ages::contains) }
    }

    fun byId(id: String?): FamilyContent? = items.firstOrNull { it.id == id }
}

/** 휴식 제안: 탑승자 중 가장 어린 나이대의 간격을 따른다. */
object RestAdvisor {
    fun intervalMin(ages: Set<AgeGroup>): Int = ages.minOfOrNull { it.restEveryMin } ?: AgeGroup.ADULT.restEveryMin

    /**
     * 다음 휴식까지 남은 분. 0 이면 지금 쉴 때. drivingMin(주행 시간, 분) 이 null 이면 null.
     * 간격마다 한 번씩 휴식을 권한다 (예: 60분 간격 → 60, 120, 180분에 0).
     */
    fun minutesUntilRest(ages: Set<AgeGroup>, drivingMin: Int?): Int? {
        val d = drivingMin ?: return null
        val interval = intervalMin(ages)
        if (d > 0 && d % interval == 0) return 0
        return interval - (d % interval)
    }
}

/** 빙고 판정: 3x3 칸에서 가로·세로·대각선 한 줄. */
object Bingo {
    private val LINES = listOf(
        listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8),
        listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8),
        listOf(0, 4, 8), listOf(2, 4, 6),
    )

    fun lines(marked: Set<Int>): Int = LINES.count { l -> l.all(marked::contains) }
}
