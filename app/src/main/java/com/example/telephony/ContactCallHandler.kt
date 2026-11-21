package com.example.telephony

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import com.example.util.FuzzyMatch

data class ContactMatch(val name: String, val phoneNumber: String, val score: Double)

class ContactCallHandler(private val context: Context) {

    private val minConfidence = 0.55

    /** Returns true if a call was placed, false if no confident match was found. */
    fun callByName(query: String): Boolean {
        val match = findBestContact(query) ?: return false
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${match.phoneNumber}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            return true
        } catch (e: Exception) {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${match.phoneNumber}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(dialIntent)
                true
            } catch (ex: Exception) {
                false
            }
        }
    }

    fun findBestContact(query: String): ContactMatch? {
        val resolver = context.contentResolver
        val cursor = try {
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, null
            )
        } catch (e: Exception) {
            null
        } ?: return null

        var best: ContactMatch? = null
        var bestScore = -1.0
        cursor.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (nameIdx < 0 || numberIdx < 0) return null

            while (it.moveToNext()) {
                val name = it.getString(nameIdx) ?: continue
                val number = it.getString(numberIdx) ?: continue
                val score = FuzzyMatch.similarity(query, name)
                if (score > bestScore) {
                    bestScore = score
                    best = ContactMatch(name, number, score)
                }
            }
        }
        return if (bestScore >= minConfidence) best else null
    }
}
