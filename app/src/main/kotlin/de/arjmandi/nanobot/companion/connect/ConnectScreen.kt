package de.arjmandi.nanobot.companion.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.arjmandi.nanobot.companion.R
import de.arjmandi.nanobot.companion.ui.Design
import de.arjmandi.nanobot.companion.ui.readingStyle
import kotlinx.coroutines.launch

/**
 * Onboarding. The composable reads one state and calls one command; every branch
 * (what counts as an address, when the key is required, which sentence a failure shows) lives in
 * [ConnectViewModel]. Layout follows the artifact's `data-view="onboarding"` in Android's own
 * metrics: system interface type, Open Sans for the paragraph, 48 dp controls, an 8 dp field radius.
 */
@Composable
fun ConnectScreen(
    model: ConnectViewModel,
    onConnected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    LaunchedEffect(model.phase) {
        if (model.phase is ConnectPhase.Connected) onConnected()
    }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.connect_kicker),
            fontSize = Design.Interface.microSize,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.connect_title),
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            text = stringResource(R.string.connect_body),
            style = readingStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp, bottom = 22.dp),
        )

        model.messageRes?.let { message ->
            RefusalSurface(stringResource(message))
        }

        Field(
            label = stringResource(R.string.connect_address_label),
            value = model.address,
            onValueChange = {
                model.address = it
                model.clearMessage()
            },
            hint = stringResource(R.string.connect_address_hint),
            keyboardType = KeyboardType.Uri,
            tag = "connect.address",
        )
        Field(
            label = stringResource(R.string.connect_key_label),
            value = model.key,
            onValueChange = {
                model.key = it
                model.clearMessage()
            },
            hint = stringResource(R.string.connect_key_hint),
            keyboardType = KeyboardType.Password,
            masked = true,
            tag = "connect.key",
            trailing = {
                Text(
                    text = stringResource(R.string.connect_key_required),
                    fontSize = Design.Interface.microSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )

        // The switch that makes a self-signed home gateway usable: off by default, and turning it
        // on does not retry: the user decides, then presses Connect.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Design.minimumTarget)
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.connect_unverified_title), fontSize = Design.Interface.bodySize)
                Text(
                    text = stringResource(R.string.connect_unverified_body),
                    fontSize = Design.Interface.captionSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = model.allowUnverifiedCertificate,
                onCheckedChange = { model.rememberTrustDecision(it) },
                modifier = Modifier.testTag("connect.allowUnverified"),
            )
        }
        if (model.trustSwitchIsTheRemedy) {
            Text(
                text = stringResource(R.string.connect_unverified_remedy),
                fontSize = Design.Interface.captionSize,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .testTag("connect.remedy"),
            )
        }

        // Monochrome, as the design system requires: primary actions are never brand orange.
        Button(
            onClick = { scope.launch { model.connect() } },
            enabled = model.canConnect,
            shape = RoundedCornerShape(Design.Radius.field),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .testTag("connect.submit"),
        ) {
            if (model.isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 8.dp).heightIn(max = 16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Text(
                text = stringResource(if (model.isConnecting) R.string.connect_connecting else R.string.connect_submit),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Text(
            text = stringResource(R.string.connect_footer),
            fontSize = Design.Interface.microSize,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 20.dp),
        )

        // The name and the mark stay, so the app says plainly what it is not, worded
        // identically on iOS, and compared by tools/parity_copy.py.
        Text(
            text = stringResource(R.string.connect_disclaimer),
            fontSize = Design.Interface.microSize,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 6.dp)
                .testTag("connect.disclaimer"),
        )
    }
}

/** The design's destructive pair at 10% fill with a 26% outline: one sentence, no stack of them. */
@Composable
private fun RefusalSurface(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .background(
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
                shape = RoundedCornerShape(Design.Radius.field),
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.26f),
                shape = RoundedCornerShape(Design.Radius.field),
            )
            .padding(horizontal = 11.dp, vertical = 9.dp)
            .testTag("connect.error"),
    ) {
        Text(
            text = text,
            fontSize = Design.Interface.captionSize,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    keyboardType: KeyboardType,
    tag: String,
    masked: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Text(
            text = label,
            fontSize = Design.Interface.microSize,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                placeholder = { Text(hint, fontSize = Design.Interface.bodySize) },
                visualTransformation = if (masked) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
                shape = RoundedCornerShape(Design.Radius.field),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Design.minimumTarget)
                    .testTag(tag),
            )
            trailing?.let {
                Column(modifier = Modifier.padding(start = 8.dp)) { it() }
            }
        }
    }
}
