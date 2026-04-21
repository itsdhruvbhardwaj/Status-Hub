package com.dhruv.statushub.ui.screens

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
import com.dhruv.statushub.R

@Composable
fun OnboardingScreen(onContinue: () -> Unit) {
    val context = LocalContext.current
    val privacyPolicyUrl = "http://sites.google.com/view/status-hub-privacy-policy/home"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.weight(1f))

        // Professional Logo Display using dedicated app_logo resource
        // Please place app_logo.png in app/src/main/res/drawable/
        Image(
            painter = painterResource(id = R.drawable.app_logo),
            contentDescription = "App Logo",
            modifier = Modifier
                .size(100.dp)
                .clip(RoundedCornerShape(22.dp)), // Clean professional rounding
            contentScale = ContentScale.Fit
        )
        
        Spacer(modifier = Modifier.height(20.dp))
        
        Text(
            text = "Status Hub",
            fontSize = 42.sp, 
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Cursive, 
            color = Color.Black
        )

        Spacer(modifier = Modifier.weight(1f))

        // Sleeker Continue Button
        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
            shape = RoundedCornerShape(50)
        ) {
            Text("Continue", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Privacy Policy Link
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