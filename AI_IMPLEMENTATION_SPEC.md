# Prompt de Implementación: IA Local para Generación de Datos Contextuales (Llama-3.2-1B)

Este documento describe la especificación técnica para integrar un modelo de lenguaje local (LLM) en el plugin **DBSeed4SQL**, permitiendo que la generación de datos sintéticos sea consciente del contexto de la aplicación definido por el usuario.

---

## 1. Arquitectura de Integración de IA

La integración se basa en un ecosistema local utilizando **Ollama** dentro de un contenedor **Docker**. El plugin debe gestionar de forma transparente el ciclo de vida del contenedor y la descarga del modelo.

### 1.1. Gestión de Infraestructura (`DockerService`)
Crear una clase de servicio que utilice el CLI de Docker o una librería ligera para:
- **Orquestación**: Verificar si Docker está instalado y en ejecución.
- **Contenedor**: Levantar un contenedor de Ollama (`ollama/ollama:latest`) mapeando el puerto `11434`.
- **Persistencia**: Mapear el directorio del host `~/Downloads/db-seed-ollama` al volumen del contenedor `/root/.ollama` para almacenar los modelos de forma persistente.
- **Model Provisioning**: Ejecutar de forma asíncrona `ollama pull llama3.2:1b` si el modelo no está presente.

### 1.2. Cliente de IA (`OllamaClient`)
Implementar un cliente utilizando `java.net.http.HttpClient` (Java 21):
- **Protocolo**: Comunicación via REST API con el endpoint `/api/generate`.
- **Estructura de Prompt**:
  ```text
  Contexto de la Aplicación: {applicationContext}
  Tabla: {tableName}
  Columna: {columnName} (Tipo: {sqlType})
  Tarea: Genera un valor realista y breve para esta columna basado en el contexto anterior.
  Respuesta: Solo el valor, sin explicaciones ni comillas.
  ```
- **Configuración**: Utilizar `temperature: 0.3` para resultados deterministas.

---

## 2. Modificaciones en el Core y Persistencia

### 2.1. Configuración de Generación (`GenerationConfig`)
Actualizar el record para incluir el contexto opcional:
```java
public record GenerationConfig(
    // ... campos existentes
    String applicationContext
) {}
```

### 2.2. Estado Global (`DbSeedSettingsState`)
Añadir campos para la URL de Ollama (`http://localhost:11434`) y el nombre del modelo (`llama3.2:1b`).

### 2.3. Orquestador de Datos (`DataGenerator`)
Refactorizar el proceso de generación:
- **Virtual Threads**: Utilizar `Executors.newVirtualThreadPerTaskExecutor()` para realizar las llamadas a la IA en paralelo, evitando que la latencia del modelo bloquee el hilo principal.
- **Estrategia de Fallback**: Si el `OllamaClient` lanza una excepción (timeout, conexión) o Docker no está listo, el sistema debe usar automáticamente el `ValueGenerator` (Faker) como respaldo.
- **Filtrado Semántico**: Solo invocar la IA para columnas candidatas (ej. `VARCHAR` con nombres como `description`, `bio`, `comment`, `product_name`), evitando columnas técnicas o IDs.

---

## 3. Interfaz de Usuario (UI)

### 3.1. Diálogo de Conexión (`SeedDialog`)
Añadir un `JBTextArea` bajo el campo de "Rows per table" con el label "Contexto de la Aplicación (Opcional)":
- **Ejemplo de uso**: "Es una aplicación para una cadena de cafeterías gourmet".
- **Persistencia**: Guardar este contexto en `ConnectionConfigPersistence` junto con el resto de la configuración de la conexión.

### 3.2. Feedback de Progreso (`AiSetupProgressDialog`)
Implementar un diálogo que se muestre al inicio si el entorno de IA está cargando:
- Mostrar una barra de progreso real para la descarga del modelo (`ollama pull`).
- Utilizar notificaciones de la IDE (`NotificationHelper`) para avisar cuando la IA esté lista para usarse.

---

## 4. Estándares de Código

- **Java 21**: Usar obligatoriamente `record`, `sealed interfaces` y Virtual Threads.
- **Documentación**: Javadocs en español para las nuevas clases explicando el propósito arquitectónico.
- **Sin Comentarios Inline**: El código debe ser auto-explicativo; prohibidos los comentarios dentro de los métodos.

---

*Este prompt define una integración moderna y eficiente, permitiendo que DBSeed4SQL genere datos que "entienden" la lógica del negocio del usuario de forma totalmente local.*
