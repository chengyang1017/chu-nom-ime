package com.example.chineseime.engine.sentence

import com.example.chineseime.engine.VietnameseInputParser

class VietnameseToneRestorer {
    fun key(value: String): String = VietnameseInputParser.withoutTone(value).trim()
    fun isExact(raw: String, restored: String): Boolean = VietnameseInputParser.normalize(raw) == VietnameseInputParser.normalize(restored)
}
