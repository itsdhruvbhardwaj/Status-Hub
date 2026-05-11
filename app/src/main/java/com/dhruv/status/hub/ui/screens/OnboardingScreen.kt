package com.dhruv.status.hub.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dhruv.status.hub.R

/**
 * OnboardingScreen Composable
 * 
 * The first screen shown to new users. It displays the app logo, name,
 * and requires the user to agree to the Privacy Policy before proceeding.
 * 
 * @param onContinue Callback triggered when the user clicks the "Continue" button.
 */
@Composable
fun OnboardingScreen(onContinue: () -> Unit) {
    val context = LocalContext.current
    val privacyPolicyUrl = "http://sites.google.com/view/status-hub-privacy-policy/home"

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Flexible spacer to push content towards the center
            Spacer(modifier = Modifier.weight(1f))

            // Application Logo
            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = "App Logo",
                modifier = Modifier
                    .size(100.dp)
                    .clip(RoundedCornerShape(22.dp)),
                contentScale = ContentScale.Fit
            )
            
            Spacer(modifier = Modifier.height(20.dp))
            
            // Application Title
            Text(
                text = "Status Hub",
                fontSize = 42.sp, 
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Cursive, 
                color = Color.Black
            )

            // Flexible spacer to push bottom elements towards the bottom
            Spacer(modifier = Modifier.weight(1f))

            // Continue Button to finish onboarding
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
                shape = RoundedCornerShape(50)
            ) {
                Text("Continue", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Privacy Policy Text with a clickable link
            val annotatedString = buildAnnotatedString {
                append("By continuing, you agree to our ")
                pushStringAnnotation(tag = "policy", annotation = privacyPolicyUrl)
                withStyle(style = SpanStyle(color = Color(0xFF2196F3), textDecoration = TextDecoration.Underline)) {
                    append("Privacy Policy")
                }
                pop()
            }

            ClickableText(
                text = annotatedString,
                style = TextStyle(
                    textAlign = TextAlign.Center, 
                    fontSize = 12.sp,
                    color = Color.Gray
                ),
                onClick = { offset ->
                    // Handle clicking the "Privacy Policy" part of the text
                    annotatedString.getStringAnnotations(tag = "policy", start = offset, end = offset)
                        .firstOrNull()?.let { annotation ->
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(annotation.item))
                            context.startActivity(intent)
                        }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
