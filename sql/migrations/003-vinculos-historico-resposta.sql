-- Aplicar após 002 e antes do workflow atualizado. Não cria vínculos históricos.
BEGIN;
-- Vínculo explícito do conjunto gravado atomicamente, sem inferir legado.
CREATE TABLE IF NOT EXISTS public.vinculos_historico_resposta (
    resposta_id integer PRIMARY KEY REFERENCES public.mensagens_whatsapp(id) ON DELETE CASCADE,
    entrada_id integer NOT NULL UNIQUE REFERENCES public.mensagens(id) ON DELETE CASCADE,
    saida_id integer NOT NULL UNIQUE REFERENCES public.mensagens(id) ON DELETE CASCADE,
    CONSTRAINT vinculos_historico_resposta_ids_distintos CHECK (entrada_id <> saida_id)
);
COMMIT;
