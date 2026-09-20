package tech.neural7.tracevanta.spi;

import tech.neural7.tracevanta.model.DataMutation;
import tech.neural7.tracevanta.model.ErrorInfo;
import tech.neural7.tracevanta.model.NodeStatus;
import tech.neural7.tracevanta.model.Payload;

/**
 * Ponto de contribuição por nó: as extensões (em ordem de {@code order()}) podem
 * renomear, enriquecer atributos e anexar payload, mutação e erro antes de o nó
 * ser congelado no TVEM.
 */
public final class NodeBuilder {

    private final MutableNode target;

    public NodeBuilder(MutableNode target) {
        this.target = target;
    }

    public NodeBuilder setLabel(String label) {
        if (label != null && !label.isBlank()) {
            target.setLabel(label);
        }
        return this;
    }

    public NodeBuilder attribute(String key, String value) {
        if (key != null && value != null) {
            target.addAttribute(key, value);
        }
        return this;
    }

    public NodeBuilder payload(Payload payload) {
        target.setPayload(payload);
        return this;
    }

    public NodeBuilder mutation(DataMutation mutation) {
        target.setMutation(mutation);
        return this;
    }

    public NodeBuilder error(ErrorInfo errorInfo) {
        target.setError(errorInfo);
        return this;
    }

    public NodeBuilder status(NodeStatus status) {
        if (status != null) {
            target.setStatus(status);
        }
        return this;
    }

    /** Contrato interno entre o núcleo e as extensões — nunca use fora da SPI. */
    public interface MutableNode {
        void setLabel(String label);

        void addAttribute(String key, String value);

        void setPayload(Payload payload);

        void setMutation(DataMutation mutation);

        void setError(ErrorInfo errorInfo);

        void setStatus(NodeStatus status);
    }
}
