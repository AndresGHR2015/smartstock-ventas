# SmartStock · Ventas

Microservicio que registra las ventas de SmartStock y mantiene el historial de demanda de cada SKU. El historial sirve tanto para consultar ventas como para evaluar la necesidad de reposición.

**Stack** · Java 21, Spring Boot, PostgreSQL y gRPC.

## Responsabilidades

- Recibir solicitudes de venta e identificar cada operación con un `sale_id`.
- Coordinar con Inventario el descuento de existencias y reflejar su resultado en el registro de la venta.
- Conservar los datos de la venta y exponer su estado por identificador para el Gateway.

## Datos y consistencia

Ventas es dueño de su registro de operaciones y del historial de demanda. Inventario es dueño del saldo. Una venta queda confirmada una vez que se completa el descuento. Si Inventario rechaza la operación por falta de unidades, la venta queda sin confirmar. Los reintentos usan el mismo identificador para que Inventario pueda reconocer una solicitud ya procesada.

## Interfaces

| Interlocutor | Canal | Propósito |
|---|---|---|
| `smartstock-gateway` | gRPC | Registrar ventas y consultarlas por identificador. |
| `smartstock-inventario` | gRPC | Solicitar el descuento de las unidades vendidas. |

Los contratos Protobuf compartidos se versionan en `smartstock-infra/contracts/`.

## Desarrollo y despliegue

Este repositorio mantiene la aplicación Spring Boot, su base PostgreSQL y sus migraciones, sus pruebas, su Dockerfile y su pipeline. La cobertura mínima exigida es del 60 %. Las pruebas del servicio verifican la confirmación de ventas, el tratamiento de rechazos y el comportamiento ante reintentos. Docker Compose y Kubernetes se coordinan desde `smartstock-infra`.

### Contratos y límites de integración

Las clases gRPC se generan desde el submódulo `vendor/smartstock-infra`, fijado al commit `6237ae7a047c3a16077db5bdd51a6ee89f5f90f1`. Inicializarlo con `git submodule update --init --recursive` antes de compilar o construir la imagen. CI necesita acceso de lectura a la URL del submódulo y al commit; su publicación y acceso deben confirmarse con el coordinador.

El contrato v1 permite consultar una venta por identificador. El historial por SKU requiere una revisión del contrato. Inventario controla el descuento idempotente mediante gRPC. Las pruebas de este repositorio usan un doble de Inventario. El flujo 10 → 7 con Inventario real se comprueba en Docker Compose de `smartstock-infra`.

### Recuperación e idempotencia

`CreateSale` inserta `PENDING` en una transacción local que termina antes de llamar a Inventario. La clave única es el SHA-256 del ID completo; se guarda y comprueba también el ID original para detectar colisiones como error técnico, nunca como otra venta. PostgreSQL serializa inserciones concurrentes con la misma clave; una repetición con otros argumentos devuelve conflicto. No se mantiene una transacción de base de datos abierta durante la llamada remota.

Solo `DEDUCTED` con producto confirma la venta. `PRODUCT_NOT_FOUND` e `INSUFFICIENT_STOCK` son rechazos definitivos. Errores gRPC, resultados desconocidos, argumentos inválidos o conflictos devueltos por Inventario dejan la venta `PENDING`; estos últimos indican una incompatibilidad y se registran como errores que requieren investigación. Una respuesta perdida después del descuento se recupera reenviando exactamente el mismo ID, SKU y cantidad.

Al arrancar y periódicamente, un proceso programado lee pendientes de PostgreSQL, ordenados por el último intento, y los reintenta en lotes; no depende de memoria de proceso. El cliente también puede reintentar `CreateSale` o consultar `GetSale`. En varias réplicas pueden coincidir intentos de la misma venta: la protección contra doble descuento es responsabilidad de Inventario, y la transición local solo actualiza filas todavía pendientes. No hay coordinación distribuida ni plazo garantizado de recuperación; se deben vigilar las ventas pendientes y los errores, especialmente si Inventario devuelve resultados incompatibles. Los reintentos son secuenciales por lote y pueden demorarse hasta el timeout por operación.

### Configuración

| Variable | Valor por defecto de desarrollo | Uso |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/sales` | PostgreSQL propio |
| `DATABASE_USER` / `DATABASE_PASSWORD` | `sales` / `sales` | Credenciales de desarrollo; sustituir en despliegue |
| `GRPC_PORT` | `9090` | Puerto de Ventas |
| `INVENTORY_TARGET` | `localhost:9091` | Destino gRPC de Inventario |
| `INVENTORY_TIMEOUT_MS` | `2000` | Plazo por intento |
| `RECOVERY_INTERVAL_MS` | `5000` | Pausa entre lotes |
| `RECOVERY_BATCH_SIZE` | `100` | Máximo de pendientes por lote |

Flyway ejecuta la migración al iniciar. La conexión gRPC configurada es plaintext y no incorpora autenticación; usar solo en una red confiable de desarrollo hasta acordar TLS y autenticación para despliegue. No usar las credenciales predeterminadas en producción.

### Pruebas reproducibles con Docker

Los siguientes comandos crean una base efímera exclusiva para pruebas. No apuntar las pruebas a una base con datos: borran las filas de `sales`.

```sh
git submodule update --init --recursive
docker network create sales-test
docker run -d --name sales-test-db --network sales-test \
  -e POSTGRES_DB=sales -e POSTGRES_USER=sales -e POSTGRES_PASSWORD=sales postgres:16
docker run --rm --network sales-test -v "$PWD:/app" -w /app \
  -e DATABASE_URL=jdbc:postgresql://sales-test-db:5432/sales \
  maven:3.9.9-eclipse-temurin-21 mvn -B verify
docker build -t smartstock-sales .
docker rm -f sales-test-db
docker network rm sales-test
```

Esperar a que PostgreSQL esté listo (`docker exec sales-test-db pg_isready -U sales -d sales`) antes de ejecutar Maven. El informe de cobertura está en `target/site/jacoco/index.html`; `verify` exige al menos 60 % de líneas de código propio, excluyendo clases generadas del contrato. La prueba de reinicio cierra y vuelve a iniciar el contexto completo de Spring con PostgreSQL persistente y un doble de Inventario que conserva sus resultados. El pipeline independiente está en `.github/workflows/ci.yml`.
