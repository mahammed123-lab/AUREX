package com.example.connectors

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.URLEncoder

object GoogleAppsBridge {

    const val GMAIL_PACKAGE = "com.google.android.gm"
    const val DRIVE_PACKAGE = "com.google.android.apps.docs"

    fun isGmailAvailable(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(GMAIL_PACKAGE, 0)
        true
    } catch (e: Exception) {
        false
    }

    fun isDriveAvailable(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(DRIVE_PACKAGE, 0)
        true
    } catch (e: Exception) {
        false
    }

    fun composeEmail(context: Context, to: String? = null, subject: String? = null, body: String? = null): Boolean {
        return try {
            // If "to" isn't an email address, try resolving it as a contact
            // name via the Contacts provider — people say "email dad", not
            // "email johndoe@gmail.com".
            var resolvedTo = to?.trim()
            if (!resolvedTo.isNullOrBlank() && !resolvedTo.contains('@')) {
                resolvedTo = lookupContactEmail(context, resolvedTo)
                // Keep the original text if no contact matched — some mail
                // apps treat a bare name as a note, better than dropping it.
            }
            val uri = StringBuilder("mailto:")
            if (!resolvedTo.isNullOrBlank()) uri.append(Uri.encode(resolvedTo))
            uri.append("?")
            subject?.takeIf { it.isNotBlank() }?.let {
                uri.append("subject=").append(Uri.encode(it)).append("&")
            }
            body?.takeIf { it.isNotBlank() }?.let {
                uri.append("body=").append(Uri.encode(it))
            }
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(uri.toString())).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage(GMAIL_PACKAGE)
            }
            val fallback = Intent(Intent.ACTION_SENDTO, Uri.parse(uri.toString())).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                context.startActivity(fallback)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Best-effort email lookup for a contact by display name. */
    private fun lookupContactEmail(context: Context, name: String): String? {
        return try {
            context.contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(
                    android.provider.ContactsContract.CommonDataKinds.Email.ADDRESS,
                    android.provider.ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY
                ),
                "${android.provider.ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY} LIKE ?",
                arrayOf("%$name%"),
                "${android.provider.ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY} ASC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun searchGmail(context: Context, query: String): Boolean {
        return try {
            val webUrl = "https://mail.google.com/mail/u/0/#search/" + URLEncoder.encode(query, "UTF-8")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage(GMAIL_PACKAGE)
            }
            val browserFallback = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                context.startActivity(browserFallback)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun openDrive(context: Context): Boolean = try {
        val launch = context.packageManager.getLaunchIntentForPackage(DRIVE_PACKAGE)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            true
        } else {
            openWebDrive(context)
        }
    } catch (e: Exception) {
        openWebDrive(context)
    }

    private fun openWebDrive(context: Context): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://drive.google.com")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        true
    } catch (e: Exception) {
        false
    }

    fun shareToDrive(context: Context, fileUri: Uri, mimeType: String): Boolean = try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, fileUri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage(DRIVE_PACKAGE)
        }
        val generic = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, fileUri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            context.startActivity(Intent.createChooser(generic, "Save to Drive").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        true
    } catch (e: Exception) {
        false
    }

    suspend fun selfTestGmail(context: Context): String = withContext(Dispatchers.IO) {
        when {
            !isNetworkReachable() -> "No network connection - email actions need internet"
            isGmailAvailable(context) -> "Connected - Gmail app found. Compose and search ready."
            else -> "Gmail app not installed - compose will fall back to any email client"
        }
    }

    suspend fun selfTestDrive(context: Context): String = withContext(Dispatchers.IO) {
        when {
            !isNetworkReachable() -> "No network connection - Drive actions need internet"
            isDriveAvailable(context) -> "Connected - Drive app found. Open and upload ready."
            else -> "Drive app not installed - will use drive.google.com in browser"
        }
    }

    suspend fun isNetworkReachable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val address = InetAddress.getByName("8.8.8.8")
            address.hostAddress != null
        } catch (e: Exception) {
            false
        }
    }
}
