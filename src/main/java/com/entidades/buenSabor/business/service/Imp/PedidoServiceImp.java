package com.entidades.buenSabor.business.service.Imp;

import com.entidades.buenSabor.business.mapper.PedidoMapper;
import com.entidades.buenSabor.business.service.*;
import com.entidades.buenSabor.business.service.Base.BaseServiceImp;
import com.entidades.buenSabor.domain.entities.Pedido;
import com.entidades.buenSabor.domain.enums.Estado;
import com.entidades.buenSabor.domain.entities.*;
import com.entidades.buenSabor.domain.enums.Rol;
import com.entidades.buenSabor.domain.enums.TipoEnvio;
import com.entidades.buenSabor.repositories.FacturaRepository;
import com.entidades.buenSabor.repositories.PedidoRepository;
import com.itextpdf.io.IOException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class PedidoServiceImp extends BaseServiceImp<Pedido,Long> implements PedidoService {


    @Autowired
    private PedidoRepository pedidoRepository;
    @Autowired
    private PedidoMapper pedidoMapper;
    @Autowired
    private ArticuloInsumoService articuloInsumoService;
    @Autowired
    private ArticuloService articuloService;
    @Autowired
    EmpleadoService empleadoService;
    @Autowired
    private FacturaService facturaService;
    @Autowired
    private EmailService emailService;
    @Autowired
    private FacturaRepository facturaRepository;


    @Override
    public Pedido create(Pedido pedido) {
        //le asignamos la fecha actual
        pedido.setFechaPedido(LocalDate.now());
        //seteamos por default el estado en pendiendte
        pedido.setEstado(Estado.PENDIENTE);
        // Asignar sucursal desde el artículo del pedido
        if (!pedido.getDetallePedidos().isEmpty()) {
            Articulo articulo = pedido.getDetallePedidos().iterator().next().getArticulo();
            if (articulo != null && articulo.getSucursal() != null) {
                pedido.setSucursal(articulo.getSucursal());
            }
        }
        validarStock(pedido.getDetallePedidos());
        aplicarDescuento(pedido);
        calcularTiempoEstimado(pedido);
        return super.create(pedido);
    }

    @Override
    public void validarStock(Set<DetallePedido> detalles) {
        for (DetallePedido detalle : detalles) {
            Articulo articulo = detalle.getArticulo();
            if (articulo instanceof ArticuloInsumo) {
                ArticuloInsumo insumo = (ArticuloInsumo) articulo;
                if (!insumo.tieneStockSuficiente(detalle.getCantidad())) {
                    throw new RuntimeException("Stock insuficiente para el artículo: " + insumo.getDenominacion());
                }
                // Decrementar el stock
                insumo.setStockActual(insumo.getStockActual() - detalle.getCantidad());
                articuloService.update(insumo, insumo.getId());
            }
        }
    }

    @Override
    public boolean aplicarDescuento(Pedido pedido) {
        if (pedido.getTipoEnvio() == TipoEnvio.TAKE_AWAY) {
            pedido.setTotal(pedido.getTotal() * 0.9); // Aplicar 10% de descuento
            return true;
        }
        return false;
    }


    @Override
    public void calcularTiempoEstimado(Pedido pedido) {
        int tiempoArticulos = pedido.getDetallePedidos().stream()
                .mapToInt(detalle -> {
                    if (detalle.getArticulo() instanceof ArticuloManufacturado) {
                        ArticuloManufacturado articuloManufacturado = (ArticuloManufacturado) detalle.getArticulo();
                        return articuloManufacturado.getTiempoEstimadoMinutos();
                    } else {
                        return 0;
                    }
                })
                .sum();
        int tiempoCocina = obtenerPedidosEnCocina().stream()
                .flatMap(p -> p.getDetallePedidos().stream())
                .mapToInt(detalle -> {
                    if (detalle.getArticulo() instanceof ArticuloManufacturado) {
                        ArticuloManufacturado articuloManufacturado = (ArticuloManufacturado) detalle.getArticulo();
                        return articuloManufacturado.getTiempoEstimadoMinutos();
                    } else {
                        return 0;
                    }
                })
                .sum();

        int cantidadCocineros = contarCocineros();
        //Si no hay cocineros disponibles, devuelve 0
        int tiempoCocinaPromedio = cantidadCocineros > 0 ? tiempoCocina / cantidadCocineros : 0;

        int tiempoDelivery = pedido.getTipoEnvio() == TipoEnvio.DELIVERY ? 10 : 0;
        pedido.setHoraEstimadaFinalizacion(LocalTime.now().plusMinutes(tiempoArticulos + tiempoCocinaPromedio + tiempoDelivery));
    }

    @Override
    public List<Pedido> obtenerPedidosEnCocina() {
        // Implementar la lógica para obtener los pedidos que están en preparación
        return pedidoRepository.findByEstado(Estado.PREPARACION);
    }

    @Override
    public int contarCocineros() {
        return empleadoService.contarPorRol(Rol.COCINERO);
    }

    @Override
    public Pedido cambiaEstado(Estado estado, Long id) {
        Pedido pedido = getById(id);
        pedido.setEstado(estado);

        if (estado == Estado.PREPARACION) {
            Factura factura = new Factura();
            factura.setFechaFacturacion(LocalDate.now());
            if (aplicarDescuento(pedido)){
                factura.setMontoDescuento(10);
            }else {
                factura.setMontoDescuento(0);
            }
            factura.setFormaPago(pedido.getFormaPago());
            factura.setTotalVenta(pedido.getTotal());
            pedido.setFactura(factura);

            facturaRepository.save(factura);
        }


        if (estado == Estado.FACTURADO) {
            try {
                // creamos la factura  la factura PDF
                byte[] facturaPdf = facturaService.generarFacturaPDF(pedido);

                // traemos el email del cliente
                String emailCliente = pedido.getCliente().getEmail();

                // Enviar el email con la factura
                emailService.sendMail(facturaPdf, emailCliente, null, "Factura de Pedido " + pedido.getId(), "Adjunto encontrará la factura de su pedido.", "factura_" + pedido.getId() + ".pdf");

            } catch (IOException | java.io.IOException e) {
                throw new RuntimeException("Error al generar o enviar la factura: " + e.getMessage(), e);
            }
        }

        return pedidoRepository.save(pedido);
    }

    @Override
    public List<Pedido> findByEstado(Estado estado) {
        return pedidoRepository.findByEstado(estado);
    }

    @Override
    public Optional<Pedido> findById(Long id) {
        return pedidoRepository.findById(id);
    }


}

