package com.example.chineseime.engine.sentence

import android.util.Log
import com.example.chineseime.data.model.NomCandidate
import com.example.chineseime.data.model.NomSentenceCandidate
import com.example.chineseime.data.model.NomSentenceSegment
import com.example.chineseime.data.repository.NomRepository
import com.example.chineseime.engine.VietnameseInputParser

enum class SentenceMatchType(val baseScore: Double) {
    EXACT_TYPED(30.0), EXACT_WITHOUT_TONE(20.0), PREFIX_TYPED(14.0), PREFIX_WITHOUT_TONE(9.0), UNKNOWN_RAW(-18.0)
}

data class IncrementalSentenceInput(
    val rawSentence: String,
    val completedTokens: List<String>,
    val currentToken: String,
    val endsWithSpace: Boolean
) {
    val tokens: List<String> get() = completedTokens + listOfNotNull(currentToken.takeIf { it.isNotEmpty() })
    companion object {
        fun parse(raw: String): IncrementalSentenceInput {
            val ends = raw.endsWith(' ')
            val parts = if (raw.isEmpty()) emptyList() else raw.split(' ')
            return if (ends) IncrementalSentenceInput(raw, parts.dropLast(1).filter { it.isNotEmpty() }, "", true)
            else IncrementalSentenceInput(raw, parts.dropLast(1).filter { it.isNotEmpty() }, parts.lastOrNull().orEmpty(), false)
        }
    }
}

class SentenceCandidateGenerator(
    private val repository: NomRepository,
    private val parser: VietnameseInputParser = VietnameseInputParser(),
    private val segmenter: NomPhraseSegmenter = NomPhraseSegmenter(),
    private val beamWidth: Int = 30
) {
    private data class Path(val position: Int, val segments: List<NomSentenceSegment>, val score: Double)
    private data class TypedCandidate(val candidate: NomCandidate, val type: SentenceMatchType)

    fun generate(rawSentence: String, limit: Int = 8): List<NomSentenceCandidate> {
        val input = IncrementalSentenceInput.parse(rawSentence)
        val tokens = input.tokens
        Log.d(TAG,"rawSentence=${input.rawSentence} endsWithSpace=${input.endsWithSpace} completedTokens=${input.completedTokens} currentToken=${input.currentToken} generationInput")
        if (tokens.isEmpty()) return emptyList()
        val beams = Array(tokens.size + 1) { mutableListOf<Path>() }; beams[0] += Path(0,emptyList(),0.0)
        for (position in tokens.indices) {
            val activePaths = beams[position].sortedByDescending { it.score }.take(beamWidth)
            for (path in activePaths) {
                for (span in segmenter.spans(tokens,position)) {
                    val rawTokens=tokens.slice(span); val phrase=rawTokens.joinToString(" ")
                    val isCurrentPrefix=!input.endsWithSpace && span.last==tokens.lastIndex
                    val typed=queryCandidates(phrase,isCurrentPrefix)
                    typed.forEach { item ->
                        val segment=segment(position,span.last+1,rawTokens,item)
                        beams[span.last+1]+=Path(span.last+1,path.segments+segment,path.score+segment.score+adjacencyBonus(path.segments.lastOrNull(),item.candidate))
                    }
                }
                val raw=tokens[position]
                val unknown=NomSentenceSegment(position,position+1,listOf(raw),raw,raw,emptyList(),SentenceMatchType.UNKNOWN_RAW.baseScore,false)
                beams[position+1]+=Path(position+1,path.segments+unknown,path.score+unknown.score)
            }
            val next=beams[position+1].sortedByDescending{it.score}.take(beamWidth)
            beams[position+1].clear();beams[position+1].addAll(next)
        }
        val results=beams[tokens.size].ifEmpty { listOf(Path(tokens.size,tokens.mapIndexed{i,t->NomSentenceSegment(i,i+1,listOf(t),t,t,emptyList(),-18.0,false)},-18.0*tokens.size)) }
            .map { path ->
                val ids=path.segments.flatMap{it.sourceEntryIds}
                NomSentenceCandidate(joinNom(path.segments),path.segments.joinToString(" "){it.restoredVietnamese},ids,path.segments,path.score+repository.sentenceHistoryScore(rawSentence.trimEnd(),ids))
            }.distinctBy{Triple(it.nomText,it.restoredVietnamese,it.sourceEntryIds)}.sortedByDescending{it.score}.take(limit)
        Log.d(TAG,"rawSentence=$rawSentence beamPaths=${beams[tokens.size].size} finalCandidates=${results.size}")
        return results
    }

    private fun queryCandidates(phrase:String,prefix:Boolean):List<TypedCandidate>{
        val parsed=parser.parse(phrase); val hasMarks=parsed.normalized!=parsed.withoutTone
        val exact=if(prefix) repository.searchReadingPrefix(parsed.normalized,80) else repository.searchExactReading(parsed.normalized,80)
        val noTone=if(prefix) repository.searchWithoutTonePrefix(parsed.withoutTone,80) else repository.searchWithoutTone(parsed.withoutTone,80)
        val exactType=if(prefix) SentenceMatchType.PREFIX_TYPED else SentenceMatchType.EXACT_TYPED
        val noToneType=if(prefix) SentenceMatchType.PREFIX_WITHOUT_TONE else SentenceMatchType.EXACT_WITHOUT_TONE
        val combined=(exact.map{TypedCandidate(it,exactType)}+noTone.map{TypedCandidate(it,noToneType)})
            .groupBy{it.candidate.sourceEntryId}.map{(_,items)->items.maxBy{candidate->candidate.type.baseScore+(if(hasMarks&&candidate.type==exactType)12.0 else 0.0)}}
        Log.d(TAG,"token=$phrase hasVietnameseMarks=$hasMarks prefix=$prefix exact=${exact.size} noTone=${noTone.size} prefixCount=${if(prefix)combined.size else 0}")
        return combined
    }

    private fun segment(start:Int,end:Int,raw:List<String>,item:TypedCandidate):NomSentenceSegment{
        val entry=item.candidate; var score=item.type.baseScore+(end-start)*4.0
        val parsed=parser.parse(raw.joinToString(" ")); val hasMarks=parsed.normalized!=parsed.withoutTone
        if(hasMarks&&VietnameseInputParser.normalize(entry.readingRaw)==parsed.normalized)score+=14.0
        if(entry.noteRaw.contains("[異]")||entry.noteRaw.contains("[俗]")||entry.noteRaw.contains("[翻]"))score-=2.5
        if(entry.readingRaw!=entry.readingRaw.lowercase())score-=2.0
        val restored=if(raw.all{it==it.lowercase()})entry.readingRaw.lowercase()else entry.readingRaw
        return NomSentenceSegment(start,end,raw,restored,entry.nomRaw,listOf(entry.sourceEntryId),score,true,entry.exampleRaw)
    }

    private fun adjacencyBonus(previous:NomSentenceSegment?,current:NomCandidate):Double{
        if(previous==null)return 0.0
        val evidence=if(current.exampleRaw.contains(previous.restoredVietnamese,true)||previous.evidenceText.contains(current.readingRaw,true))3.0 else 0.0
        return evidence+(previous.sourceEntryIds.lastOrNull()?.let{repository.ngramScore(it,current.sourceEntryId)}?:0.0)
    }
    private fun joinNom(segments:List<NomSentenceSegment>)=buildString{segments.forEachIndexed{i,s->if(!s.isConverted&&i>0)append(' ');append(s.nomText);if(!s.isConverted&&i<segments.lastIndex)append(' ')}}
    companion object{private const val TAG="NOM_IME"}
}
