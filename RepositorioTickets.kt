package com.fabiancg.mpazticket

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

/**
 * Repositorio de tickets con base de datos local (Room / SQLite). Todos los datos son ficticios.
 *
 * Funcionamiento: la lista de tickets vive en memoria (StateFlow) para que las pantallas se actualicen al instante,
 * y cada cambio se guarda además en la base de datos. Al abrir la app, [iniciar] vuelve a cargar todo desde la base.
 */
object RepositorioTickets {
    private val usuariosIniciales = listOf(
        Usuario(1, "Ana Rojas", "Profesora", Rol.SOLICITANTE, "ana", "1234"),
        Usuario(2, "Luis Soto", "Secretario", Rol.SOLICITANTE, "luis", "1234"),
        Usuario(3, "Carla Muñoz", "Encargada de TI", Rol.ADMINISTRADOR, "admin", "1234"),
        Usuario(4, "Pedro Díaz", "Auxiliar de mantención", Rol.RESPONSABLE, "pedro", "1234"),
        Usuario(5, "Marta Vega", "Técnico de TI", Rol.RESPONSABLE, "tecnico", "1234")
    )

    /** Usuarios cargados desde la base de datos (disponibles después de [iniciar]). */
    var usuarios: List<Usuario> = emptyList()
        private set

    private val _tickets = MutableStateFlow<List<Ticket>>(emptyList())
    val tickets: StateFlow<List<Ticket>> = _tickets
    private var contador = 0

    private var dao: TicketDao? = null

    // Un solo hilo de escritura: los guardados se hacen en el mismo orden en que ocurren los cambios.
    private val escritura = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())

    private class Datos(
        val usuarios: List<UsuarioEntity>, val tickets: List<TicketEntity>,
        val comentarios: List<ComentarioEntity>, val eventos: List<EventoEntity>
    )

    /** Abre la base de datos y carga los datos. Se llama una vez al iniciar la app (ver MainActivity). */
    fun iniciar(contexto: Context) {
        if (dao != null) return
        val d = BaseDatos.obtener(contexto).ticketDao()
        dao = d
        // Lectura inicial breve (pocos datos): se espera el resultado para que la primera pantalla ya tenga todo.
        val datos = runBlocking(Dispatchers.IO) { Datos(d.usuarios(), d.tickets(), d.comentarios(), d.eventos()) }
        if (datos.usuarios.isEmpty()) {
            // Primera vez que se abre la app: se cargan los usuarios y los tickets de ejemplo.
            usuarios = usuariosIniciales
            escritura.launch { d.guardarUsuarios(usuariosIniciales.map { it.aEntidad() }) }
            sembrar()
        } else {
            usuarios = datos.usuarios.map { it.aModelo() }
            _tickets.value = datos.tickets.map { t ->
                t.aModelo(datos.comentarios.filter { it.ticketId == t.id }, datos.eventos.filter { it.ticketId == t.id })
            }
            contador = _tickets.value.maxOfOrNull { it.id } ?: 0
        }
    }

    /** Guarda en la base de datos el ticket [id] con sus comentarios e historial. */
    private fun guardar(id: Int) {
        val d = dao ?: return
        val t = _tickets.value.firstOrNull { it.id == id } ?: return
        escritura.launch {
            try {
                d.guardarCompleto(t.aEntidad(), t.comentariosEntidad(), t.eventosEntidad())
            } catch (e: Exception) {
                Log.e("MPAZ", "No se pudo guardar el ticket ${t.codigo}", e)
            }
        }
    }

    private fun ahora() = System.currentTimeMillis()
    fun usuario(id: Int?): Usuario? = usuarios.firstOrNull { it.id == id }
    fun autenticar(u: String, c: String): Usuario? =
        usuarios.firstOrNull { it.usuario == u.trim().lowercase() && it.clave == c }

    fun crear(
        s: Usuario, titulo: String, desc: String, cat: Categoria,
        sub: String, ubic: String, fotos: List<String>
    ): Ticket {
        contador++
        val t = Ticket(
            id = contador, codigo = "MPAZ-%04d".format(contador), titulo = titulo, descripcion = desc,
            categoria = cat, subcategoria = sub, ubicacion = ubic, fotos = fotos,
            solicitanteId = s.id, creado = ahora(),
            historial = listOf(
                Evento(
                    ahora(), "Ticket creado",
                    "Categoría: ${cat.etiqueta} · $sub\nUbicación: $ubic\nFotografías adjuntas: ${fotos.size}",
                    "${s.nombre} · ${s.cargo}"
                )
            )
        )
        _tickets.update { it + t }
        guardar(t.id)
        return t
    }

    /** detalle, recibe el ticket ANTES del cambio, para poder explicar "de X a Y". */
    private fun modificar(
        id: Int, autor: Usuario, titulo: String?,
        detalle: (Ticket) -> String = { "" },
        cambio: (Ticket) -> Ticket
    ) {
        _tickets.update { lista ->
            lista.map { t ->
                if (t.id != id) t else {
                    val n = cambio(t)
                    if (titulo == null) n
                    else n.copy(historial = n.historial + Evento(ahora(), titulo, detalle(t), "${autor.nombre} · ${autor.cargo}"))
                }
            }
        }
        guardar(id)
    }

    fun priorizar(id: Int, admin: Usuario, cat: Categoria, prio: Prioridad) =
        modificar(id, admin, "Categoría y prioridad definidas",
            detalle = { t ->
                buildString {
                    append("Categoría: ${t.categoria.etiqueta} → ${cat.etiqueta}")
                    append("\nPrioridad: ${t.prioridad.etiqueta} → ${prio.etiqueta}")
                    if (t.estado == Estado.PENDIENTE) append("\nEstado: Pendiente → Revisado")
                }
            }
        ) {
            it.copy(
                categoria = cat,
                subcategoria = if (cat == it.categoria) it.subcategoria else Catalogo.subcategorias.getValue(cat).first(),
                prioridad = prio,
                estado = if (it.estado == Estado.PENDIENTE) Estado.REVISADO else it.estado
            )
        }

    fun asignar(id: Int, admin: Usuario, resp: Usuario) =
        modificar(id, admin, "Asignación de responsable",
            detalle = { t ->
                val anterior = usuario(t.asignadoA)
                buildString {
                    if (anterior == null) append("Responsable asignado: ${resp.nombre} (${resp.cargo})")
                    else append("Reasignado de ${anterior.nombre} a ${resp.nombre} (${resp.cargo})")
                    if (t.estado in listOf(Estado.PENDIENTE, Estado.REVISADO)) append("\nEstado: ${t.estado.etiqueta} → Asignado")
                }
            }
        ) {
            it.copy(
                asignadoA = resp.id, fechaAsignacion = ahora(),
                estado = if (it.estado in listOf(Estado.PENDIENTE, Estado.REVISADO, Estado.ASIGNADO)) Estado.ASIGNADO else it.estado
            )
        }

    fun cambiarEstado(id: Int, autor: Usuario, nuevo: Estado) =
        modificar(id, autor, "Cambio de estado",
            detalle = { t ->
                "De «${t.estado.etiqueta}» a «${nuevo.etiqueta}»" + when (nuevo) {
                    Estado.SOLUCIONADO -> "\nSe registró la fecha de solución."
                    Estado.CERRADO -> "\nSe confirmó la solución y se registró la fecha de cierre."
                    Estado.CANCELADO -> "\nEl ticket fue cancelado."
                    else -> ""
                }
            }
        ) {
            it.copy(
                estado = nuevo,
                fechaSolucion = if (nuevo == Estado.SOLUCIONADO) ahora() else it.fechaSolucion,
                fechaCierre = if (nuevo == Estado.CERRADO) ahora() else it.fechaCierre
            )
        }

    fun comentar(id: Int, autor: Usuario, texto: String) =
        modificar(id, autor, null) {
            it.copy(comentarios = it.comentarios + Comentario(autor.nombre, autor.rol, texto, ahora()))
        }

    fun registrarAvance(id: Int, autor: Usuario, acciones: String, materiales: String) =
        modificar(id, autor, "Acciones registradas",
            detalle = {
                "Acciones realizadas: ${acciones.ifBlank { "(sin descripción)" }}" +
                        "\nMateriales o información requerida: ${materiales.ifBlank { "ninguna" }}"
            }
        ) { it.copy(acciones = acciones, materiales = materiales) }

    fun agregarEvidencia(id: Int, autor: Usuario, ruta: String) =
        modificar(id, autor, "Evidencia adjuntada",
            detalle = { "Se adjuntó una fotografía de la reparación (total de evidencias: ${it.evidencia.size + 1})." }
        ) { it.copy(evidencia = it.evidencia + ruta) }

    /** Tickets de ejemplo (solo se crean la primera vez que se abre la app). */
    private fun sembrar() {
        val (ana, luis, admin, pedro) = usuarios
        crear(ana, "Proyector no enciende", "El proyector no enciende desde el lunes.",
            Categoria.TECNOLOGIA, "Proyector", "Sala de clases - 5.º A", emptyList())
        val t2 = crear(luis, "Sillas con patas sueltas", "Dos sillas inestables en la zona de lectura.",
            Categoria.MOBILIARIO, "Sillas", "Biblioteca - Zona de lectura", emptyList())
        priorizar(t2.id, admin, Categoria.MOBILIARIO, Prioridad.MEDIA)
        asignar(t2.id, admin, pedro)
        val t3 = crear(ana, "Filtración de agua", "Gotea agua desde el techo del baño.",
            Categoria.INFRAESTRUCTURA, "Plomería / baños", "Baños - Primer piso", emptyList())
        priorizar(t3.id, admin, Categoria.INFRAESTRUCTURA, Prioridad.ALTA)
        asignar(t3.id, admin, pedro)
        cambiarEstado(t3.id, pedro, Estado.EN_PROCESO)
    }
}
