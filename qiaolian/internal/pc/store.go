package pc

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
)

type tokenStore struct {
	path   string
	mu     sync.Mutex
	tokens map[string]string
}

func loadTokens(path string) *tokenStore {
	s := &tokenStore{path: path, tokens: map[string]string{}}
	raw, err := os.ReadFile(path)
	if err == nil {
		_ = json.Unmarshal(raw, &s.tokens)
	}
	if s.tokens == nil {
		s.tokens = map[string]string{}
	}
	return s
}

func (s *tokenStore) Get(id string) string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.tokens[id]
}

func (s *tokenStore) Put(id, token string) {
	s.mu.Lock()
	s.tokens[id] = token
	raw, err := json.MarshalIndent(s.tokens, "", "  ")
	path := s.path
	s.mu.Unlock()
	if err != nil {
		return
	}
	_ = os.MkdirAll(filepath.Dir(path), 0o700)
	_ = os.WriteFile(path, raw, 0o600)
}
