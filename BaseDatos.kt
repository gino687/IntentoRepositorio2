package com.fabiancg.mpazticket

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

// ------------------------------------------------------------------ TABLAS
// Los enums se guardan como texto (su nombre) y las listas de rutas de fotos como texto separado por saltos de línea.

@Entity(tableName = "usuarios")
data class UsuarioEntity(
    @PrimaryKey val id: Int,
    val nombre: String,
    val cargo: String,
    val rol: String,
    val usuario: String,
    val clave: String
)

@Entity(tableName = "tickets")
data class TicketEntity(
    @PrimaryKey val id: Int,
    val codigo: String,
    val titulo: String,
    val descripcion: String,
    val categoria: String,
    val subcategoria: String,
    val ubicacion: String,
    val fotos: String,
    val solicitanteId: Int,
    val creado: Long,
    val prioridad: String,
    val estado: String,
    val asignadoA: Int?,
    val fechaAsignacion: Long?,
    val acciones: String,
    val materiales: String,
    val evidencia: String,
    val fechaSolucion: Long?,
    val fechaCierre: Long?
)

@Entity(tableName = "comentarios", indices = [Index("ticketId")])
data class ComentarioEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ticketId: Int,
    val autor: String,
    val rol: String,
    val texto: String,
    val fecha: Long
)

@Entity(tableName = "eventos", indices = [Index("ticketId")])
data class EventoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ticketId: Int,
    val fecha: Long,
    val titulo: String,
    val detalle: String,
    val autor: String
)

// ------------------------------------------------------------------ CONSULTAS
@Dao
abstract class TicketDao {
    @Query("SELECT * FROM usuarios")
    abstract suspend fun usuarios(): List<UsuarioEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun guardarUsuarios(lista: List<UsuarioEntity>)

    @Query("SELECT * FROM tickets")
    abstract suspend fun tickets(): List<TicketEntity>

    @Query("SELECT * FROM comentarios ORDER BY fecha, id")
    abstract suspend fun comentarios(): List<ComentarioEntity>

    @Query("SELECT * FROM eventos ORDER BY fecha, id")
    abstract suspend fun eventos(): List<EventoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun guardarTicket(ticket: TicketEntity)

    @Insert
    abstract suspend fun guardarComentarios(lista: List<ComentarioEntity>)

    @Insert
    abstract suspend fun guardarEventos(lista: List<EventoEntity>)

    @Query("DELETE FROM comentarios WHERE ticketId = :ticketId")
    abstract suspend fun borrarComentarios(ticketId: Int)

    @Query("DELETE FROM eventos WHERE ticketId = :ticketId")
    abstract suspend fun borrarEventos(ticketId: Int)

    /** Guarda un ticket junto con sus comentarios e historial, todo o nada. */
    @Transaction
    open suspend fun guardarCompleto(ticket: TicketEntity, comentarios: List<ComentarioEntity>, eventos: List<EventoEntity>) {
        guardarTicket(ticket)
        borrarComentarios(ticket.id)
        borrarEventos(ticket.id)
        guardarComentarios(comentarios)
        guardarEventos(eventos)
    }
}

// ------------------------------------------------------------------ BASE DE DATOS
@Database(
    entities = [UsuarioEntity::class, TicketEntity::class, ComentarioEntity::class, EventoEntity::class],
    version = 1,
    exportSchema = false
)
abstract class BaseDatos : RoomDatabase() {
    abstract fun ticketDao(): TicketDao

    companion object {
        @Volatile
        private var instancia: BaseDatos? = null

        fun obtener(contexto: Context): BaseDatos =
            instancia ?: synchronized(this) {
                instancia ?: Room.databaseBuilder(
                    contexto.applicationContext, BaseDatos::class.java, "mpaz_ticket.db"
                ).build().also { instancia = it }
            }
    }
}

// ------------------------------------------------------------------ CONVERSIONES (modelo <-> tabla)
private const val SEPARADOR = "\n"
private fun List<String>.unir(): String = joinToString(SEPARADOR)
private fun String.separar(): List<String> = if (isBlank()) emptyList() else split(SEPARADOR).filter { it.isNotBlank() }

fun Usuario.aEntidad() = UsuarioEntity(id, nombre, cargo, rol.name, usuario, clave)
fun UsuarioEntity.aModelo() = Usuario(id, nombre, cargo, Rol.valueOf(rol), usuario, clave)

fun Ticket.aEntidad() = TicketEntity(
    id = id, codigo = codigo, titulo = titulo, descripcion = descripcion,
    categoria = categoria.name, subcategoria = subcategoria, ubicacion = ubicacion,
    fotos = fotos.unir(), solicitanteId = solicitanteId, creado = creado,
    prioridad = prioridad.name, estado = estado.name, asignadoA = asignadoA,
    fechaAsignacion = fechaAsignacion, acciones = acciones, materiales = materiales,
    evidencia = evidencia.unir(), fechaSolucion = fechaSolucion, fechaCierre = fechaCierre
)

fun Ticket.comentariosEntidad() = comentarios.map {
    ComentarioEntity(ticketId = id, autor = it.autor, rol = it.rol.name, texto = it.texto, fecha = it.fecha)
}

fun Ticket.eventosEntidad() = historial.map {
    EventoEntity(ticketId = id, fecha = it.fecha, titulo = it.titulo, detalle = it.detalle, autor = it.autor)
}

fun TicketEntity.aModelo(comentarios: List<ComentarioEntity>, eventos: List<EventoEntity>) = Ticket(
    id = id, codigo = codigo, titulo = titulo, descripcion = descripcion,
    categoria = Categoria.valueOf(categoria), subcategoria = subcategoria, ubicacion = ubicacion,
    fotos = fotos.separar(), solicitanteId = solicitanteId, creado = creado,
    prioridad = Prioridad.valueOf(prioridad), estado = Estado.valueOf(estado),
    asignadoA = asignadoA, fechaAsignacion = fechaAsignacion,
    comentarios = comentarios.map { Comentario(it.autor, Rol.valueOf(it.rol), it.texto, it.fecha) },
    acciones = acciones, materiales = materiales, evidencia = evidencia.separar(),
    fechaSolucion = fechaSolucion, fechaCierre = fechaCierre,
    historial = eventos.map { Evento(it.fecha, it.titulo, it.detalle, it.autor) }
)
