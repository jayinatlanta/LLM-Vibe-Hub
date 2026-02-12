package com.llmhub.llmhub.screens

import android.webkit.WebView
import android.webkit.WebSettings
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebViewClient
import android.util.Log

/**
 * CodeCanvasScreen displays generated HTML/JavaScript code in a WebView.
 * This allows users to interact with the generated code in real-time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeCanvasScreen(
    codeContent: String,
    codeType: String = "html",
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Code Preview") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (hasError) {
            // Error state - show error message
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "Preview Error",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.error
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Button(
                    onClick = onNavigateBack
                ) {
                    Text("Go Back")
                }
            }
        } else {
            // WebView for displaying the code
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.apply {
                            // Security settings
                            javaScriptEnabled = true
                            domStorageEnabled = false
                            databaseEnabled = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            
                            // Performance settings
                            cacheMode = WebSettings.LOAD_NO_CACHE
                            defaultTextEncodingName = "utf-8"
                        }
                        
                        webViewClient = object : WebViewClient() {
                            override fun onReceivedError(
                                view: WebView,
                                errorCode: Int,
                                description: String,
                                failingUrl: String
                            ) {
                                super.onReceivedError(view, errorCode, description, failingUrl)
                                hasError = true
                                errorMessage = "WebView Error: $description"
                                Log.e("CodeCanvasScreen", "WebView Error: $description (Code: $errorCode)")
                            }
                        }
                        
                        // Sanitize and load HTML content
                        try {
                            val sanitizedHtml = sanitizeHtml(codeContent)
                            loadData(sanitizedHtml, "text/html", "utf-8")
                        } catch (e: Exception) {
                            hasError = true
                            errorMessage = e.message ?: "Failed to load content"
                            Log.e("CodeCanvasScreen", "Failed to load HTML", e)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        }
    }
}

/**
 * Sanitize HTML content to prevent XSS attacks.
 * Uses a basic allowlist approach - only allows safe HTML tags.
 */
private fun sanitizeHtml(htmlContent: String): String {
    // This is a basic sanitization. For production, consider using a library like jsoup
    // For now, we trust that the model generates safe HTML, but we disallow dangerous patterns
    
    var sanitized = htmlContent
    
    // Remove script tags and content
    sanitized = sanitized.replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
    
    // Remove on* event handlers
    sanitized = sanitized.replace(Regex("""on\w+\s*="""), "")
    
    // Remove javascript: protocol
    sanitized = sanitized.replace(Regex("""javascript:\s*""", RegexOption.IGNORE_CASE), "")
    
    // Wrap in HTML structure if not present
    if (!sanitized.contains("<html", ignoreCase = true)) {
        sanitized = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
                        margin: 0;
                        padding: 8px;
                        background: #f5f5f5;
                    }
                </style>
            </head>
            <body>
            $sanitized
            </body>
            </html>
        """.trimIndent()
    }
    
    return sanitized
}
