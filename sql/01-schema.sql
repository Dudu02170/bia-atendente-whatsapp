-- Tabelas usadas pelo fluxo da Bia (leads e histórico de mensagens).
-- Executado automaticamente pelo Postgres na primeira vez que o container sobe.


CREATE TABLE public.leads (
    id integer NOT NULL,
    telefone character varying(20) NOT NULL,
    nome character varying(255),
    cidade character varying(100),
    estado character varying(2),
    produto_interesse text,
    necessidade text,
    tipo_projeto character varying(100),
    experiencia_resina boolean DEFAULT false,
    status character varying(50) DEFAULT 'novo'::character varying,
    bot_ativo boolean DEFAULT true,
    resumo_handoff text,
    criado_em timestamp without time zone DEFAULT now(),
    atualizado_em timestamp without time zone DEFAULT now()
);

CREATE SEQUENCE public.leads_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.leads_id_seq OWNED BY public.leads.id;

CREATE TABLE public.leads_whatsapp (
    id integer NOT NULL,
    telefone text NOT NULL,
    nome text,
    etapa_atual text DEFAULT 'inicio'::text,
    projeto text,
    experiencia text,
    cidade text,
    produto_interesse text,
    bot_ativo boolean DEFAULT true,
    criado_em timestamp without time zone DEFAULT now(),
    atualizado_em timestamp without time zone DEFAULT now()
);

CREATE SEQUENCE public.leads_whatsapp_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.leads_whatsapp_id_seq OWNED BY public.leads_whatsapp.id;

CREATE TABLE public.mensagens (
    id integer NOT NULL,
    lead_id integer,
    telefone character varying(20) NOT NULL,
    direcao character varying(10) NOT NULL,
    conteudo text NOT NULL,
    intencao character varying(50),
    criado_em timestamp without time zone DEFAULT now()
);

CREATE SEQUENCE public.mensagens_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.mensagens_id_seq OWNED BY public.mensagens.id;

CREATE TABLE public.mensagens_whatsapp (
    id integer NOT NULL,
    telefone text NOT NULL,
    nome text,
    role text NOT NULL,
    mensagem text NOT NULL,
    message_id text,
    tipo_mensagem text,
    created_at timestamp without time zone DEFAULT now()
);

CREATE SEQUENCE public.mensagens_whatsapp_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.mensagens_whatsapp_id_seq OWNED BY public.mensagens_whatsapp.id;

ALTER TABLE ONLY public.leads ALTER COLUMN id SET DEFAULT nextval('public.leads_id_seq'::regclass);

ALTER TABLE ONLY public.leads_whatsapp ALTER COLUMN id SET DEFAULT nextval('public.leads_whatsapp_id_seq'::regclass);

ALTER TABLE ONLY public.mensagens ALTER COLUMN id SET DEFAULT nextval('public.mensagens_id_seq'::regclass);

ALTER TABLE ONLY public.mensagens_whatsapp ALTER COLUMN id SET DEFAULT nextval('public.mensagens_whatsapp_id_seq'::regclass);

ALTER TABLE ONLY public.leads
    ADD CONSTRAINT leads_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.leads
    ADD CONSTRAINT leads_telefone_key UNIQUE (telefone);

ALTER TABLE ONLY public.leads_whatsapp
    ADD CONSTRAINT leads_whatsapp_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.leads_whatsapp
    ADD CONSTRAINT leads_whatsapp_telefone_key UNIQUE (telefone);

ALTER TABLE ONLY public.mensagens
    ADD CONSTRAINT mensagens_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.mensagens_whatsapp
    ADD CONSTRAINT mensagens_whatsapp_message_id_key UNIQUE (message_id);

ALTER TABLE ONLY public.mensagens_whatsapp
    ADD CONSTRAINT mensagens_whatsapp_pkey PRIMARY KEY (id);

CREATE INDEX idx_leads_status ON public.leads USING btree (status);

CREATE INDEX idx_leads_telefone ON public.leads USING btree (telefone);

CREATE INDEX idx_mensagens_criado ON public.mensagens USING btree (criado_em DESC);

CREATE INDEX idx_mensagens_lead_id ON public.mensagens USING btree (lead_id);

CREATE INDEX idx_mensagens_telefone ON public.mensagens USING btree (telefone);

CREATE TRIGGER trigger_leads_atualizar BEFORE UPDATE ON public.leads FOR EACH ROW EXECUTE FUNCTION public.atualizar_timestamp();

ALTER TABLE ONLY public.mensagens
    ADD CONSTRAINT mensagens_lead_id_fkey FOREIGN KEY (lead_id) REFERENCES public.leads(id);


