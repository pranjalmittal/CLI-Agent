# WinCliAgent

An agent written in **Java** (JDK 17+) that takes a natural-language prompt, converts it into a **Windows CLI command** using the OpenAI API, screens it for safety, executes it, and shows the output.

## How it works

```
prompt --> [generate] --> [safety] --> (execute | blocked) --> output
             (GPT)         (screen)      (PowerShell)
```

- **generate** (`agent.llm.OpenAiClient`) — GPT translates your request into a single Windows command (`dir`, `type`, `ipconfig`, `Get-ChildItem`, ...). The system prompt forbids Unix-only commands.
- **safety** (`agent.safety.SafetyFilter` + `agent.safety.CommandNormalizer`) — a pre-compiled regex screen blocks obviously destructive commands (`format`, `shutdown`, mass delete, `diskpart`, execution policy bypass, etc.). Before screening, the command is de-obfuscated and decomposed so nested and hidden payloads are caught (see Safety below).
- **execute** (`agent.executor.CommandExecutor`) — the command runs via native Windows shells or cross-platform PowerShell Core (`pwsh`), capturing stdout, stderr, and exit codes with configurable timeout.

## Architecture

- **Zero External Dependencies**: Uses only standard Java SE libraries (`java.net.http.HttpClient`, `java.util.regex`, `java.lang.ProcessBuilder`).
- **Standard Layout**:
  ```
  src/
    main/java/agent/
      config/AgentConfig.java        # Loads .env & system env variables
      executor/CommandExecutor.java  # Runs commands via pwsh or cmd with timeouts
      llm/OpenAiClient.java          # OpenAI HTTP/2 client & JSON parser
      model/AgentState.java          # Pipeline state
      model/ExecutionResult.java     # Execution record (stdout, stderr, exitCode)
      pipeline/AgentPipeline.java    # Workflow coordinator (generate -> safety -> execute)
      safety/SafetyFilter.java       # Destructive command screening
      safety/CommandNormalizer.java  # Decodes and decomposes commands before screening
      Main.java                      # CLI entry point (REPL & one-shot)
    test/java/agent/
      AgentTest.java                 # Standalone unit test suite
  pom.xml                            # Optional Maven build configuration
  run.sh / run.bat                   # Zero-dependency execution scripts
  test.sh                            # Zero-dependency test runner
  Dockerfile                         # Java 17 + PowerShell Core
  docker-compose.yml
  .env.example                       # API key configuration template
  ```

## Safety

The safety screen fails closed. `CommandNormalizer` expands a command line into every form
that must be checked, and `SafetyFilter` screens each one:

| Evasion | Handled by |
| --- | --- |
| `pwsh -EncodedCommand <base64>` | Base64 payload decoded (UTF-16LE and UTF-8) and screened; undecodable payloads are blocked as opaque |
| `cmd /c format C:` | Nested shell wrappers unwrapped and screened |
| `& { format }` | Script blocks and call operators unwrapped |
| `` fo`rmat `` | Backtick escapes stripped |
| `"fo"+"rmat"` | Literal concatenation folded into the resolved command name |
| `Get-ChildItem \| Remove-Item -Recurse` | Quote-aware split on `;`, `&&`, `\|\|`, `\|`; every segment screened |

Blocked commands are never executed and there is no override flag. Variable-driven invocation
(`& $cmd`) cannot be resolved statically and is screened on its literal text only, so the
screen remains a guardrail rather than a sandbox.

Known conservative behavior: a destructive keyword appearing inside a quoted argument
(`echo "the shutdown was cancelled"`) is still blocked.

Execution is bounded: output is capped at 1 MiB per stream (excess is counted and discarded
while the stream is still drained), timeouts escalate from a graceful terminate to a forced
kill of the whole process tree, and partial output from a timed-out command is preserved.

## Configuration

Copy `.env.example` to `.env` or export environment variables:

```bash
OPENAI_API_KEY=sk-...
OPENAI_MODEL=gpt-4o-mini
COMMAND_TIMEOUT_SECONDS=60
```

## Running Locally

### Option 1: Direct Script (No build tools needed)

```bash
# Make executable
chmod +x run.sh test.sh

# Run one-shot (with confirmation prompt)
./run.sh "list all files in the current directory"

# Run with auto-execution (no confirmation prompt)
./run.sh --auto "show running processes"

# Interactive REPL
./run.sh
```

### Option 2: Pure Java (javac / java)

```bash
mkdir -p target/classes
javac -d target/classes $(find src/main/java -name "*.java")
java -cp target/classes agent.Main "list files"
```

### Option 3: Maven (if installed)

```bash
mvn compile
mvn exec:java -Dexec.mainClass="agent.Main" -Dexec.args="--auto 'list files'"
```

## Running Tests

Run the standalone test suite (verifies safety screening, JSON parsing, and state flow):

```bash
./test.sh
```

Or manually:
```bash
mkdir -p target/classes target/test-classes
javac -d target/classes $(find src/main/java -name "*.java")
javac -cp target/classes -d target/test-classes $(find src/test/java -name "*.java")
java -cp target/classes:target/test-classes agent.AgentTest
```

## Docker (Full Execution with PowerShell Core)

To test Windows command emulation inside Docker on Linux or macOS:

```bash
# Build and start
docker compose build
docker compose up -d

# Run command inside container
docker compose exec wincliagent ./run.sh --auto "Get-ChildItem"

# Run test suite inside container
docker compose exec wincliagent ./test.sh

# Stop container
docker compose down
```

## CLI Options

| Flag / Option | Description |
|---|---|
| `[prompt...]` | The natural language request to translate and run |
| `--auto`, `-y` | Execute the generated command without asking for confirmation |
| `--help`, `-h` | Display usage information |
| `--version`, `-v` | Display version information |
