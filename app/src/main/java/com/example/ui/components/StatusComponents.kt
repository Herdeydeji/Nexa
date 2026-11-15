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
import com.example.ui.theme.NexaTheme

@Composable
fun NexaSurfaceCard(
    modifier: Modifier = Modifier,
    containerColor: Color = NexaTheme.colors.secondaryBackground,
    borderColor: Color = NexaTheme.colors.borderSubtle,
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
        textColor = NexaTheme.colors.orange,
        containerColor = NexaTheme.colors.orangeMuted,
        modifier = modifier,
        showDot = false
    )
}

@Composable
fun TaskStatusPill(status: AgentTaskStatus, modifier: Modifier = Modifier) {
    val colors = NexaTheme.colors
    val (fg, bg) = when (status) {
        AgentTaskStatus.RUNNING -> colors.orange to colors.orangeMuted
        AgentTaskStatus.AWAITING_APPROVAL,
        AgentTaskStatus.WAITING_FOR_USER_UNLOCK,
        AgentTaskStatus.WAITING_FOR_PERMISSION -> colors.warning to colors.warningContainer
        AgentTaskStatus.WAITING_FOR_EXTERNAL_APP,
        AgentTaskStatus.VERIFYING,
        AgentTaskStatus.PAUSED -> colors.info to colors.infoContainer
        AgentTaskStatus.COMPLETED -> colors.success to colors.successContainer
        AgentTaskStatus.PARTIALLY_COMPLETED -> colors.warning to colors.warningContainer
        AgentTaskStatus.FAILED -> colors.error to colors.errorContainer
        AgentTaskStatus.CANCELLED -> colors.neutral to colors.neutralContainer
    }
    StatusPill(label = status.label, textColor = fg, containerColor = bg, modifier = modifier)
}

@Composable
fun CapabilityStatusPill(
    status: CapabilityStatus,
    modifier: Modifier = Modifier,
    labelOverride: String? = null
) {
    val colors = NexaTheme.colors
    val (fg, bg) = when (status) {
        CapabilityStatus.AVAILABLE -> colors.success to colors.successContainer
        CapabilityStatus.PERMISSION_REQUIRED -> colors.warning to colors.warningContainer
        CapabilityStatus.RESTRICTED -> colors.error to colors.errorContainer
        CapabilityStatus.UNAVAILABLE -> colors.neutral to colors.neutralContainer
        CapabilityStatus.NOT_CONFIGURED -> colors.info to colors.infoContainer
        CapabilityStatus.TEMPORARILY_UNAVAILABLE -> colors.warning to colors.warningContainer
    }
    StatusPill(label = labelOverride ?: status.label, textColor = fg, containerColor = bg, modifier = modifier)
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
            color = NexaTheme.colors.textSecondary
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = NexaTheme.colors.textSecondary
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
    val colors = NexaTheme.colors
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
                .background(colors.elevatedSurface)
                .border(1.dp, colors.border, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
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
    accentColor: Color = NexaTheme.colors.orange,
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
            color = NexaTheme.colors.textPrimary
        )
    }
}

