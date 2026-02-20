package com.openclaw.assistant.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openclaw.assistant.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentBottomSheet(
    onDismiss: () -> Unit,
    onCameraClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onFileClick: () -> Unit,
    showCamera: Boolean = true
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            if (showCamera) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.camera)) },
                    leadingContent = {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                    },
                    modifier = Modifier.clickable {
                        onDismiss()
                        onCameraClick()
                    }
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.gallery)) },
                leadingContent = {
                    Icon(Icons.Default.Image, contentDescription = null)
                },
                modifier = Modifier.clickable {
                    onDismiss()
                    onGalleryClick()
                }
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.choose_file)) },
                leadingContent = {
                    Icon(Icons.Default.InsertDriveFile, contentDescription = null)
                },
                modifier = Modifier.clickable {
                    onDismiss()
                    onFileClick()
                }
            )
        }
    }
}
