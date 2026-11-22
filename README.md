# SmartStock · Ventas

Microservicio que registra las ventas de SmartStock y mantiene el historial de demanda de cada SKU. El historial sirve tanto para consultar ventas como para evaluar la necesidad de reposición.

**Stack** · Java 21, Spring Boot, PostgreSQL y gRPC.

## Responsabilidades

- Recibir solicitudes de venta e identificar cada operación con un `sale_id`.
- Coordinar con Inventario el descuento de existencias y reflejar su resultado en el registro de la venta.
- Conservar los datos de la venta y exponer el historial por SKU para su consulta desde el Gateway.

## Datos y consistencia

Ventas es dueño de su registro de operaciones y del historial de demanda. Inventario es dueño del saldo. Una venta queda confirmada una vez que se completa el descuento. Si Inventario rechaza la operación por falta de unidades, la venta queda sin confirmar. Los reintentos usan el mismo identificador para que Inventario pueda reconocer una solicitud ya procesada.

## Interfaces

| Interlocutor | Canal | Propósito |
|---|---|---|
| `smartstock-gateway` | gRPC | Registrar ventas y consultar su historial. |
| `smartstock-inventario` | gRPC | Solicitar el descuento de las unidades vendidas. |

Los contratos Protobuf compartidos se versionan en `smartstock-infra/contracts/`.

## Desarrollo y despliegue

Este repositorio mantiene la aplicación Spring Boot, su base PostgreSQL y sus migraciones, sus pruebas, su Dockerfile y su pipeline. La cobertura mínima exigida es del 60 %. Las pruebas del servicio verifican la confirmación de ventas, el tratamiento de rechazos y el comportamiento ante reintentos. Docker Compose y Kubernetes se coordinan desde `smartstock-infra`.
