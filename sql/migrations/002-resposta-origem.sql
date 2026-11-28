-- Atualiza o schema anterior sem recriar tabelas ou inferir vínculos históricos.
-- Aplicar antes de importar o workflow que grava mensagem_origem_id.
BEGIN;
ALTER TABLE public.mensagens_whatsapp
    ADD COLUMN IF NOT EXISTS mensagem_origem_id integer;
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.mensagens_whatsapp'::regclass
          AND conname = 'mensagens_whatsapp_mensagem_origem_fkey'
    ) THEN
        ALTER TABLE public.mensagens_whatsapp
            ADD CONSTRAINT mensagens_whatsapp_mensagem_origem_fkey
            FOREIGN KEY (mensagem_origem_id) REFERENCES public.mensagens_whatsapp(id)
            ON DELETE SET NULL;
    END IF;
END;
$$;
CREATE INDEX IF NOT EXISTS idx_mensagens_whatsapp_origem
    ON public.mensagens_whatsapp(mensagem_origem_id);
COMMIT;
