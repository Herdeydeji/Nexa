package com.example.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.ComposerAttachment
import com.example.ui.theme.NexaBorder
import com.example.ui.theme.NexaElevatedSurface
import com.example.ui.theme.NexaOnOrange
import com.example.ui.theme.NexaOrange
import com.example.ui.theme.NexaSecondaryBackground
import com.example.ui.theme.NexaTextPrimary
import com.example.ui.theme.NexaTextSecondary
import com.example.ui.theme.NexaTextTertiary

@Composable
fun ChatComposer(
    draftText: String,
    attachments: List<ComposerAttachment>,
    isResponding: Boolean,
    onDraftChange: (String) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSendClick: () -> Unit,
    onStopClick: () -> Unit,
    onAttachClick: () -> Unit,
    onVoiceClick: () -> Unit,
    onToolsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canSend = draftText.isNotBlank() || attachments.isNotEmpty()

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = NexaSecondaryBackground,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(width = 1.dp, color = NexaBorder, shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            if (attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    attachments.forEach { attachment ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(NexaElevatedSurface)
                                .border(1.dp, NexaBorder, RoundedCornerShape(10.dp))
                                .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = attachment.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = NexaTextPrimary
                            )
                            IconButton(
                                onClick = { onRemoveAttachment(attachment.id) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "Remove attachment",
                                    tint = NexaTextSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(NexaElevatedSurface)
                    .border(1.dp, NexaBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                BasicTextField(
                    value = draftText,
                    onValueChange = onDraftChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("chat_composer_input"),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = NexaTextPrimary),
                    cursorBrush = SolidColor(NexaOrange),
                    maxLines = 5,
                    decorationBox = { innerTextField ->
                        if (draftText.isEmpty()) {
                            Text(
                                text = stringResource(R.string.chat_input_placeholder),
                                style = MaterialTheme.typography.bodyLarge,
                                color = NexaTextTertiary
                            )
                        }
                        innerTextField()
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(
                        onClick = onAttachClick,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("composer_attach_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AttachFile,
                            contentDescription = stringResource(R.string.chat_attach),
                            tint = NexaTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = onVoiceClick,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("composer_voice_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Mic,
                            contentDescription = stringResource(R.string.chat_voice),
                            tint = NexaTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = onToolsClick,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("composer_tools_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Build,
                            contentDescription = stringResource(R.string.chat_tools),
                            tint = NexaTextSecondary,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }

                if (isResponding) {
                    Surface(
                        onClick = onStopClick,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("composer_stop_button"),
                        shape = CircleShape,
                        color = NexaElevatedSurface
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Stop,
                                contentDescription = stringResource(R.string.chat_stop),
                                tint = NexaOrange,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                } else {
                    Surface(
                        onClick = { if (canSend) onSendClick() },
                        enabled = canSend,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("composer_send_button"),
                        shape = CircleShape,
                        color = if (canSend) NexaOrange else NexaElevatedSurface
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Send,
                                contentDescription = stringResource(R.string.chat_send),
                                tint = if (canSend) NexaOnOrange else NexaTextTertiary,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
