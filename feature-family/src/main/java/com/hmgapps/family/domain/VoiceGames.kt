package com.hmgapps.family.domain

import kotlin.random.Random

/**
 * 2. 음성 놀이 (화면 대신 목소리로): TTS 로 말하고 STT 로 듣는다.
 * 게임 로직은 순수 Kotlin — 말할 문장과 '이제 들을지'만 돌려주고, 실제 음성 입출력은 FamilyViewModel 이 한다.
 */
enum class GameKind(val label: String, val emoji: String, val blurb: String) {
    WORD_CHAIN("끝말잇기", "🔤", "앱과 번갈아 끝말을 이어요"),
    RIDDLE("스무고개", "🕵", "힌트를 듣고 정답을 맞혀요"),
    REGION("지역 퀴즈", "🗺", "지금 지나는 지역 특산물·명소 퀴즈"),
}

/** 게임 한 차례의 결과. say 를 읽고, listen 이면 이어서 마이크로 대답을 듣는다. */
data class Turn(
    val say: String,
    val listen: Boolean,
    val finished: Boolean = false,
    /** 화면에 크게 보여 줄 짧은 안내 (예: "'차'로 시작하는 말"). */
    val prompt: String? = null,
)

interface VoiceGame {
    val kind: GameKind
    /** 화면 기록용: (누가, 무엇을) — 앱 = false, 우리 = true. */
    val history: List<Pair<Boolean, String>>
    val score: Int
    fun start(): Turn
    /** 음성 인식 결과(또는 직접 입력). */
    fun onHeard(text: String): Turn
    /** 아무 말도 못 알아들었을 때. */
    fun onSilence(): Turn
    /** '힌트/넘기기' 버튼. */
    fun skip(): Turn
}

/** 같은 질문에 몇 번까지 다시 들을지. 그 뒤에는 버튼을 기다린다 (계속 마이크를 켜 두지 않는다). */
private const val MAX_SILENCE = 2

// ---------------------------------------------------------------- 끝말잇기

class WordChainGame(private val rnd: Random = Random.Default, private val maxRounds: Int = 12) : VoiceGame {
    override val kind = GameKind.WORD_CHAIN
    override val history = mutableListOf<Pair<Boolean, String>>()
    override var score = 0
        private set

    private val used = mutableSetOf<String>()
    private var last: String = ""
    private var misses = 0
    private var silences = 0
    private var over = false

    private val needChar: Char get() = last.last()
    private fun needText(): String = Hangul.allowedStarts(needChar).joinToString(" 또는 ") { "'$it'" }

    override fun start(): Turn {
        val w = WordBank.STARTERS.random(rnd)
        appSays(w)
        return Turn("끝말잇기 시작! 내가 먼저 할게. ${w}! 이제 ${needText()}(으)로 시작하는 말을 말해 줘.", listen = true, prompt = promptText())
    }

    override fun onHeard(text: String): Turn {
        if (over) return Turn("끝말잇기가 끝났어. 다시 하려면 시작을 눌러 줘.", listen = false, finished = true)
        silences = 0
        val candidates = Hangul.words(text).map(Hangul::stripEnding).filter { it.length >= 2 }
        if (candidates.isEmpty()) return onSilence()
        val starts = Hangul.allowedStarts(needChar)
        val ok = candidates.firstOrNull { it.first() in starts && it !in used }
        if (ok == null) {
            misses++
            val said = candidates.first()
            val why = if (said in used) "'$said'는 벌써 나왔어!" else "'$said'는 ${needText()}(으)로 시작하지 않아."
            if (misses >= 3) {
                over = true
                return Turn("$why 이번 판은 내가 이겼다! 그래도 ${score}번이나 이었어. 다시 해 볼까?", listen = false, finished = true)
            }
            return Turn("$why 다시 말해 볼래?", listen = true, prompt = promptText())
        }
        misses = 0
        used += ok
        history += true to ok
        score++
        last = ok
        if (score >= maxRounds) {
            over = true
            return Turn("${ok}! 와, ${score}번이나 이었어! 오늘의 끝말잇기 왕이야!", listen = false, finished = true)
        }
        val reply = pickReply()
        if (reply == null) {
            over = true
            return Turn("${ok}! 으악, 생각이 안 나! 네가 이겼어! 우리 ${score}번이나 이었어.", listen = false, finished = true)
        }
        appSays(reply)
        return Turn("${ok}! 좋아. 그럼 나는 ${reply}! ${needText()}(으)로 시작하는 말!", listen = true, prompt = promptText())
    }

    override fun onSilence(): Turn {
        if (over) return Turn("", listen = false, finished = true)
        silences++
        return if (silences > MAX_SILENCE) {
            silences = 0
            Turn("생각나면 '말하기' 버튼을 눌러 줘. 기다릴게.", listen = false, prompt = promptText())
        } else {
            Turn("잘 안 들렸어. ${needText()}(으)로 시작하는 말을 다시 말해 줘.", listen = true, prompt = promptText())
        }
    }

    override fun skip(): Turn {
        if (over) { reset(); return start() }
        val hint = WordBank.startingWith(needChar).firstOrNull { it !in used }
        return if (hint == null) Turn("${needText()}(으)로 시작하는 말은 나도 어렵네. 아무 말이나 해 봐!", listen = true, prompt = promptText())
        else Turn("힌트! ${hint.first()}${"○".repeat(hint.length - 1)}. ${hint.length}글자야.", listen = true, prompt = promptText())
    }

    private fun reset() { used.clear(); history.clear(); score = 0; misses = 0; silences = 0; over = false }

    private fun appSays(w: String) {
        used += w
        history += false to w
        last = w
    }

    /** 아이가 이어 가기 쉬운 말(끝 글자로 시작하는 낱말이 많은 것)을 앞쪽에서 고른다. */
    private fun pickReply(): String? {
        val options = WordBank.startingWith(needChar).filter { it !in used }
        if (options.isEmpty()) return null
        val easy = options.filter { WordBank.continuations(it) >= 2 }.ifEmpty { options }
        return easy.random(rnd)
    }

    private fun promptText() = "${needText()}(으)로 시작하는 말"
}

// ---------------------------------------------------------------- 스무고개 (힌트를 하나씩 듣고 맞히기)

data class Riddle(val answer: String, val aliases: List<String>, val hints: List<String>)

object RiddleBank {
    val ALL = listOf(
        Riddle("기린", listOf(), listOf("나는 동물이에요.", "다리가 길어요.", "몸에 갈색 무늬가 있어요.", "높은 나무의 잎을 먹어요.", "목이 아주아주 길어요.")),
        Riddle("펭귄", listOf(), listOf("나는 새예요.", "그런데 하늘을 날지 못해요.", "추운 곳에 살아요.", "뒤뚱뒤뚱 걸어요.", "검은 옷에 흰 배를 가졌어요.")),
        Riddle("수박", listOf(), listOf("나는 먹을 수 있어요.", "여름에 많이 먹어요.", "겉은 초록색 줄무늬예요.", "속은 빨갛고 까만 씨가 있어요.", "아주 크고 무거운 과일이에요.")),
        Riddle("무지개", listOf(), listOf("하늘에서 볼 수 있어요.", "비가 그친 뒤에 잘 보여요.", "여러 가지 색깔이 있어요.", "둥근 다리 모양이에요.", "빨주노초파남보!")),
        Riddle("소방차", listOf("소방 차"), listOf("나는 탈것이에요.", "빨간색이에요.", "사이렌 소리를 내요.", "긴 사다리와 호스가 있어요.", "불을 끄러 달려가요.")),
        Riddle("비행기", listOf(), listOf("나는 탈것이에요.", "날개가 있어요.", "공항에서 만날 수 있어요.", "하늘 높이 날아요.", "여행 갈 때 타고 바다를 건너요.")),
        Riddle("눈사람", listOf(), listOf("겨울에만 만날 수 있어요.", "하얀색이에요.", "동그라미 두 개로 만들어요.", "당근 코를 붙여 주기도 해요.", "햇볕이 나면 녹아요.")),
        Riddle("바나나", listOf(), listOf("나는 과일이에요.", "노란색이에요.", "길쭉하고 휘어 있어요.", "껍질을 벗겨 먹어요.", "원숭이가 좋아해요.")),
        Riddle("거북이", listOf("거북"), listOf("나는 동물이에요.", "아주 오래 살 수 있어요.", "느릿느릿 움직여요.", "단단한 등딱지가 있어요.", "토끼와 달리기 시합을 한 이야기로 유명해요.")),
        Riddle("자전거", listOf(), listOf("나는 탈것이에요.", "엔진이 없어요.", "바퀴가 두 개예요.", "발로 페달을 굴려요.", "넘어지지 않게 헬멧을 써요.")),
        Riddle("달", listOf("보름달"), listOf("밤하늘에 있어요.", "모양이 날마다 조금씩 바뀌어요.", "추석에 소원을 빌어요.", "토끼가 산다는 이야기가 있어요.", "둥근 보름달이 될 때도 있어요.")),
        Riddle("우산", listOf(), listOf("들고 다니는 물건이에요.", "접었다 펼 수 있어요.", "비 오는 날 꼭 필요해요.", "펼치면 둥근 지붕 같아요.", "비를 막아 줘요.")),
        Riddle("터널", listOf("굴"), listOf("고속도로에서 자주 만나요.", "산을 뚫어서 만들어요.", "들어가면 어두워져요.", "안에서는 전조등을 켜요.", "나오면 다시 환해져요.")),
        Riddle("휴게소", listOf(), listOf("고속도로에 있어요.", "화장실이 있어요.", "맛있는 간식을 팔아요.", "운전하다 피곤하면 들러요.", "여기서 쉬어 가요!")),
    )
}

class RiddleGame(private val rnd: Random = Random.Default, private val pool: List<Riddle> = RiddleBank.ALL) : VoiceGame {
    override val kind = GameKind.RIDDLE
    override val history = mutableListOf<Pair<Boolean, String>>()
    override var score = 0
        private set

    private var riddle: Riddle = pool.first()
    private var hint = 0
    private var silences = 0
    private val done = mutableSetOf<String>()

    val hintNo: Int get() = hint + 1
    val hintCount: Int get() = riddle.hints.size

    override fun start(): Turn {
        riddle = pool.filter { it.answer !in done }.ifEmpty { done.clear(); pool }.random(rnd)
        hint = 0
        silences = 0
        history.clear()
        history += false to riddle.hints[0]
        return Turn("스무고개! 내가 생각한 걸 맞혀 봐. 첫 번째 힌트. ${riddle.hints[0]}", listen = true, prompt = "힌트 1")
    }

    override fun onHeard(text: String): Turn {
        silences = 0
        val said = Hangul.compact(text)
        if (said.isEmpty()) return onSilence()
        if (said.contains("모르") || said.contains("힌트") || said.contains("몰라")) return skip()
        history += true to Hangul.words(text).joinToString(" ")
        val names = listOf(riddle.answer) + riddle.aliases
        if (names.any { said.contains(Hangul.compact(it)) }) {
            score++
            done += riddle.answer
            return Turn("딩동댕! 정답은 ${riddle.answer}! 힌트 ${hint + 1}개 만에 맞혔어. 한 문제 더 할까? '다음 문제'를 눌러 줘.", listen = false, finished = true, prompt = "정답: ${riddle.answer}")
        }
        return nextHint(prefix = "땡! 아니야.")
    }

    override fun onSilence(): Turn {
        silences++
        return if (silences > MAX_SILENCE) {
            silences = 0
            Turn("정답이 떠오르면 '말하기' 버튼을 눌러 줘.", listen = false, prompt = "힌트 ${hint + 1}")
        } else Turn("잘 안 들렸어. 정답이 뭘까?", listen = true, prompt = "힌트 ${hint + 1}")
    }

    override fun skip(): Turn = nextHint(prefix = "좋아, 힌트 하나 더!")

    private fun nextHint(prefix: String): Turn {
        if (hint >= riddle.hints.lastIndex) {
            done += riddle.answer
            return Turn("$prefix 정답은 ${riddle.answer}였어! 다음엔 꼭 맞혀 보자.", listen = false, finished = true, prompt = "정답: ${riddle.answer}")
        }
        hint++
        history += false to riddle.hints[hint]
        return Turn("$prefix ${hint + 1}번째 힌트. ${riddle.hints[hint]}", listen = true, prompt = "힌트 ${hint + 1}")
    }
}

// ---------------------------------------------------------------- 지역 퀴즈

class RegionQuizGame(
    private val region: Region?,
    private val rnd: Random = Random.Default,
) : VoiceGame {
    override val kind = GameKind.REGION
    override val history = mutableListOf<Pair<Boolean, String>>()
    override var score = 0
        private set

    private val questions = RegionQuizBank.forRegion(region).shuffled(rnd)
    private var index = 0
    private var silences = 0
    private var tries = 0
    val current: RegionQuestion get() = questions[index % questions.size]

    override fun start(): Turn {
        index = 0
        return ask(intro = region?.let { "지금 ${it.label} 지나는 중! " } ?: "지역 퀴즈! ")
    }

    private fun ask(intro: String = ""): Turn {
        silences = 0
        tries = 0
        val q = current
        history += false to q.question
        // 질문에 이미 '지금 ○○ 지나는 중'이 있으면 앞말을 겹쳐 읽지 않는다
        val lead = if (q.question.startsWith("지금")) "" else intro
        return Turn(lead + q.question, listen = true, prompt = q.region.label)
    }

    override fun onHeard(text: String): Turn {
        silences = 0
        val said = Hangul.compact(text)
        if (said.isEmpty()) return onSilence()
        history += true to Hangul.words(text).joinToString(" ")
        val q = current
        if (said.contains("모르") || said.contains("몰라")) return reveal("괜찮아!")
        if (q.keywords.any { said.contains(Hangul.compact(it)) }) {
            score++
            return next("딩동댕! ${q.explain}")
        }
        tries++
        return if (tries >= 2) reveal("아쉽다!") else Turn("땡! 한 번 더 생각해 볼까?", listen = true, prompt = q.region.label)
    }

    private fun reveal(prefix: String): Turn = next("$prefix ${current.explain}")

    /** 설명을 읽고 다음 문제로 넘어간다 (설명 뒤엔 바로 다음 문제를 묻지 않고 버튼을 기다린다). */
    private fun next(say: String): Turn {
        index++
        return Turn("$say 다음 문제는 '다음 문제'를 눌러 줘.", listen = false, finished = true, prompt = "맞힌 문제 ${score}개")
    }

    fun nextQuestion(): Turn = ask()

    override fun onSilence(): Turn {
        silences++
        return if (silences > MAX_SILENCE) {
            silences = 0
            Turn("정답이 떠오르면 '말하기' 버튼을 눌러 줘.", listen = false, prompt = current.region.label)
        } else Turn("잘 안 들렸어. 다시 말해 줄래?", listen = true, prompt = current.region.label)
    }

    override fun skip(): Turn = reveal("정답을 알려 줄게.")
}
