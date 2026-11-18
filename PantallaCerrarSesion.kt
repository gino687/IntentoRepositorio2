package com.fabiancg.mpazticket

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Pantalla de confirmación: cerrar sesión o volver a la página principal del usuario. */
@Composable
fun PantallaCerrarSesion(vm: TicketViewModel, onConfirmar: () -> Unit, onVolver: () -> Unit) {
    val usuarioActual by vm.usuario.collectAsState()
    val u = usuarioActual

    Scaffold(topBar = { BarraSoloVolver(onVolver) }) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Aparecer(0) { TituloPantalla("Cerrar sesión") }
            Aparecer(1) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("¿Desea cerrar su sesión?", style = MaterialTheme.typography.titleMedium)
                        if (u != null) Text("Sesión activa: ${u.nombre} · ${u.rol.etiqueta}")
                        Text("Para volver a usar la aplicación deberá ingresar nuevamente con su usuario y clave.")
                    }
                }
            }
            Aparecer(2) {
                Button(
                    onClick = onConfirmar,
                    colors = ButtonDefaults.buttonColors(containerColor = MoradoMPAZ, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Cerrar sesión") }
            }
            Aparecer(3) {
                OutlinedButton(onClick = onVolver, modifier = Modifier.fillMaxWidth()) {
                    Text("Volver a la Cuenta")
                }
            }
        }
    }
}
