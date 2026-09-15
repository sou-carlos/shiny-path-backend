# Shiny Path Backend

Projeto Maven independente em Java 25 e Spring Boot 4.1.1, com Spring Security,
Spring Data JPA, PostgreSQL e Flyway. O frontend React fica na pasta irmã `../shiny-path`.

## Executar com Docker

Requer Docker com Compose. No diretório `shiny-path-backend`:

```powershell
Copy-Item .env.example .env
docker compose up --build -d
```

O PostgreSQL fica em `localhost:5432` e a API em `http://localhost:8080`.
O arquivo `.env.example` contém credenciais **somente de desenvolvimento**.
Os dados ficam no volume `postgres-data`. `docker compose down` encerra os serviços
sem excluir os dados. Não use `down -v` se quiser preservar as contas.

Para executar somente o banco pelo Docker:

```powershell
docker compose up -d postgres
```

## Executar com Java 25

Instale um JDK 25, defina `JAVA_HOME` e configure um PostgreSQL.
Crie um banco chamado `shiny_path` e um usuário com permissão de criar tabelas nesse banco.
O Maven Wrapper incluído baixa o Maven; não é necessário instalá-lo separadamente.

No PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4'
$env:DB_URL = 'jdbc:postgresql://localhost:5432/shiny_path'
$env:DB_USERNAME = 'shiny_path'
$env:DB_PASSWORD = 'shiny_path_local_dev'
.\mvnw.cmd spring-boot:run
```

No Linux/macOS, configure as mesmas variáveis e execute `./mvnw spring-boot:run`.
O Spring não carrega `.env` automaticamente na execução Java; use variáveis de ambiente.
O Compose carrega `.env` automaticamente.

As migrações `V1__create_users.sql` e `V2__user_progress.sql` criam as contas e a tabela de progresso. O Hibernate apenas valida o esquema.

## Frontend

Na pasta irmã do frontend:

```powershell
cd ../shiny-path
npm install
npm run dev
```

Abra `http://localhost:5173`. O Vite encaminha `/api` para `http://localhost:8080`.
O mesmo proxy funciona em `npm run preview` na porta 4173.
A aplicação começa no login. O cadastro cria a conta no PostgreSQL e inicia a sessão.

## API

| Método | Rota | Comportamento |
|---|---|---|
| GET | /api/auth/csrf | Cria/retorna o token CSRF e o nome do cabeçalho |
| POST | /api/auth/register | Cria a conta e inicia a sessão; retorna 201 |
| POST | /api/auth/login | Autentica e inicia a sessão; retorna 200 |
| GET | /api/auth/me | Retorna a conta da sessão ou 401 |
| POST | /api/auth/logout | Invalida a sessão; retorna 204 |

Cadastro: `{"name":"Scot","email":"scot@example.com","password":"uma-senha-forte"}`

Login: `{"email":"scot@example.com","password":"uma-senha-forte"}`

Para cada POST, obtenha `/csrf` e envie o token no cabeçalho retornado, conservando
os cookies da resposta. Os testes reproduzem esse fluxo com um cliente HTTP real.
Os tokens mudam após login/cadastro; obtenha um novo token antes do próximo POST.

A resposta autenticada contém `id`, `name`, `email` e `createdAt`. Nunca inclui a senha
ou o hash. Erros usam `code`, `message` e, quando aplicável, `fieldErrors`.

## Autenticação

- Senhas de 8 a 128 caracteres, com hash PBKDF2 e salt aleatório.
- Emails normalizados e protegidos por restrição única no PostgreSQL.
- Cookie `SHINY_PATH_SESSION`, HttpOnly, SameSite=Lax, duração da sessão de 8 horas de inatividade.
- Proteção CSRF também no login, cadastro e logout.
- Rotação do identificador da sessão ao autenticar.
- CORS permite somente as origens explícitas em `APP_ALLOWED_ORIGINS`.
- As sessões ficam em memória no backend. Reiniciar o backend exige novo login.
- Contas e progresso são persistidos no PostgreSQL por usuário e restaurados em novos acessos.

## Configuração de produção

Publique frontend e API na mesma origem com um proxy reverso que encaminhe `/api`
para o backend e as demais rotas para o `index.html` do frontend.
Use HTTPS, `COOKIE_SECURE=true`, credenciais próprias e a origem exata em
`APP_ALLOWED_ORIGINS`. O servidor estático de produção não inclui o proxy do Vite.

`VITE_API_URL` permite informar outra URL da API ao compilar o frontend. Prefira
a mesma origem; cookies SameSite=Lax não atendem hospedagens em sites distintos.
Não coloque senhas do banco em variáveis `VITE_*`.

## Verificar

```powershell
.\mvnw.cmd verify
```

Os testes sobem um PostgreSQL temporário isolado usando `embedded-postgres` (somente
dependência de teste, sem H2 e sem Docker), executam as migrações e iniciam a API em
porta aleatória. Cobrem cadastro, hash, normalização e duplicidade de email,
validação, login inválido, sessão, logout, CSRF e CORS. Nenhum banco configurado em
`DB_URL` é usado pelos testes. Na primeira execução são baixados os binários do
PostgreSQL pelo Maven.

Build de produção: `./mvnw -DskipTests package`.
O JAR é gerado em `target/shiny-path-backend-0.0.1-SNAPSHOT.jar`.

Referências: [requisitos do Spring Boot](https://docs.spring.io/spring-boot/system-requirements.html),
[CSRF no Spring Security](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
e [PostgreSQL embarcado para testes](https://github.com/zonkyio/embedded-postgres).


## Persistência de progresso

`GET /api/progress` retorna `{userId, revision, document, lastWriteId}` da conta autenticada.
`POST /api/progress` recebe `{userId, revision, writeId, document}` com cookie e token CSRF.
O usuário é identificado pela sessão; a API não aceita um identificador de outra conta.

O documento JSONB armazena aulas e desbloqueios, apresentações, pontos, vidas, streaks,
acertos e tentativas, conquistas, medalhas e IDs das aulas já recompensadas. A gravação
é transacional. Uma revisão desatualizada retorna 409; a repetição do último `writeId`
com o mesmo documento retorna o resultado anterior, sem aplicar novamente a gravação.

O frontend serializa as gravações, informa falhas e aguarda o salvamento antes do logout.
Ao fechar com alterações pendentes, o navegador solicita confirmação. A sincronização
ocorre ao salvar e ao abrir a aplicação; abas antigas recebem conflito ao tentar gravar.
Medalhas e apresentação locais são importadas somente quando ainda não há documento no servidor.
A pontuação é calculada no cliente; esta API valida e persiste o estado e não substitui
as regras de aula por avaliação de respostas no servidor. Ao alterar o catálogo de aulas,
atualize também a validação de IDs/quantidade em `ProgressController` e seus testes.
