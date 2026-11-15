package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.model.AgentTaskStatus
import com.example.domain.model.CapabilityStatus
import com.example.ui.theme.NexaBorder
import com.example.ui.theme.NexaBorderSubtle
import com.example.ui.theme.NexaElevatedSurface
import com.example.ui.theme.NexaError
import com.example.ui.theme.NexaErrorContainer
import com.example.ui.theme.NexaInfo
import com.example.ui.theme.NexaInfoContainer
import com.example.ui.theme.NexaNeutral
import com.example.ui.theme.NexaNeutralContainer
import com.example.ui.theme.NexaOrange
import com.example.ui.theme.NexaOrangeMuted
import com.example.ui.theme.NexaSecondaryBackground
import com.example.ui.theme.NexaSuccess
import com.example.ui.theme.NexaSuccessContainer
import com.example.ui.theme.NexaTextPrimary
import com.example.ui.theme.NexaTextSecondary
import com.example.ui.theme.NexaWarning
import com.example.ui.theme.NexaWarningContainer

@Composable
fun NexaSurfaceCard(
    modifier: Modifier = Modifier,
    containerColor: Color = NexaSecondaryBackground,
    borderColor: Color = NexaBorderSubtle,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = containerColor,
            border = BorderStroke(1.dp, borderColor)
        ) {
            Column(modifier = Modifier.padding(16.dp), content = content)
        }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = containerColor,
            border = BorderStroke(1.dp, borderColor)
        ) {
            Column(modifier = Modifier.padding(16.dp), content = content)
        }
    }
}

@Composable
fun StatusPill(
    label: String,
    textColor: Color,
    containerColor: Color,
    modifier: Modifier = Modifier,
    showDot: Boolean = true
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(containerColor)
            .border(1.dp, textColor.copy(alpha = 0.25f), RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (showDot) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(textColor)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun SampleDataBadge(modifier: Modifier = Modifier) {
    StatusPill(
        label = "SAMPLE PREVIEW",
        textColor = NexaOrange,
        containerColor = NexaOrangeMuted,
        modifier = modifier,
        showDot = false
    )
}

@Composable
fun TaskStatusPill(status: AgentTaskStatus, modifier: Modifier = Modifier) {
    val (fg, bg) = when (status) {
        AgentTaskStatus.RUNNING -> NexaOrange to NexaOrangeMuted
        AgentTaskStatus.AWAITING_APPROVAL -> NexaWarning to NexaWarningContainer
        AgentTaskStatus.COMPLETED -> NexaSuccess to NexaSuccessContainer
        AgentTaskStatus.FAILED -> NexaError to NexaErrorContainer
    }
    StatusPill(label = status.label, textColor = fg, containerColor = bg, modifier = modifier)
}

@Composable
fun CapabilityStatusPill(status: CapabilityStatus, modifier: Modifier = Modifier) {
    val (fg, bg) = when (status) {
        CapabilityStatus.AVAILABLE -> NexaSuccess to NexaSuccessContainer
        CapabilityStatus.PERMISSION_REQUIRED -> NexaWarning to NexaWarningContainer
        CapabilityStatus.RESTRICTED -> NexaError to NexaErrorContainer
        CapabilityStatus.UNAVAILABLE -> NexaNeutral to NexaNeutralContainer
        CapabilityStatus.NOT_CONFIGURED -> NexaInfo to NexaInfoContainer
    }
    StatusPill(label = status.label, textColor = fg, containerColor = bg, modifier = modifier)
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = NexaTextSecondary
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = NexaTextSecondary
            )
        }
    }
}

@Composable
fun EmptyStateView(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(NexaElevatedSurface)
                .border(1.dp, NexaBorder, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = NexaTextSecondary,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = NexaTextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = NexaTextSecondary,
            textAlign = TextAlign.Center
        )
        if (action != null) {
            Spacer(modifier = Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
fun NoticeBanner(
    icon: ImageVector,
    text: String,
    accentColor: Color = NexaOrange,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(accentColor.copy(alpha = 0.09f))
            .border(1.dp, accentColor.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = NexaTextPrimary
        )
    }
}
