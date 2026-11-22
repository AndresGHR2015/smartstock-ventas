# SmartStock · Ventas

Microservicio que registra ventas y conserva el historial de demanda por SKU. Coordina el descuento de existencias con Inventario.

**Stack** · Java 21, Spring Boot, PostgreSQL y gRPC.

## Alcance del MVP

- Recibir del Gateway una venta con su identificador, SKU y cantidad.
- Solicitar a Inventario el descuento por gRPC y confirmar la venta cuando el descuento se completa.
- Exponer el historial de ventas por SKU para el análisis de reposición.

## Integraciones

| Conexión | Protocolo | Operación |
|---|---|---|
| Gateway → Ventas | gRPC | Registrar una venta y consultar el historial. |
| Ventas → Inventario | gRPC | Invocar `DeductStock(sale_id, sku, quantity)`. |

Ante un reintento de la misma venta, Ventas conserva el `sale_id` para que Inventario pueda reconocer la operación. El contrato de Inventario se mantiene en `smartstock-infra/contracts/proto/inventory/v1/inventory.proto`.

## Primera verificación

Registrar una venta de 3 unidades y comprobar que Inventario descuenta esas unidades. Si la cantidad supera el saldo disponible, la venta debe quedar sin confirmar.

El historial se guarda en la base PostgreSQL de Ventas. El repo tendrá su Dockerfile, su pipeline y una exigencia de cobertura mínima del 60 %. El despliegue en Kubernetes se integra desde `smartstock-infra`.
